package com.idkidknow.nixcoursiercache

import cats.MonadThrow
import cats.effect.ExitCode
import cats.effect.IO
import cats.effect.IOApp
import cats.effect.kernel.Concurrent
import cats.effect.std.Console
import cats.syntax.all.*
import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.core.writeToString
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import com.monovore.decline.Command
import com.monovore.decline.Opts
import fs2.Stream
import fs2.io.file.Files
import fs2.io.file.Path
import fs2.io.process.ProcessBuilder
import fs2.io.process.Processes

import java.nio.file.Path as JPath

object App extends IOApp {
  override def run(args: List[String]): IO[ExitCode] = {
    val root = Opts
      .option[JPath](
        long = "coursier-cache",
        short = "c",
        help = "coursier cache directory (e.g. ~/.cache/coursier/v1)",
        metavar = "path",
      )
      .map(Path.fromNioPath)
      .map(_.absolute.normalize)

    val out = Opts
      .option[JPath](
        long = "out",
        short = "o",
        help = "output file (output to stdout if not specified)",
        metavar = "path",
      )
      .map(Path.fromNioPath)
      .orNone

    val artifactArgs =
      Opts.arguments[JPath]("artifacts").map(_.map(Path.fromNioPath)).orEmpty
    val artifactListFile = Opts
      .option[JPath](
        long = "artifact-list",
        help = """
          |Text file containing '\n' separated list of artifacts.
          |Extra artifacts can be specified in command line arguments.
          |If no artifacts are specified, all files in the cache are listed.
          |The paths should be either absolute or relative to the coursier cache directory.
          |""".stripMargin.strip,
      )
      .map(Path.fromNioPath)
      .orNone
    val rootAndArtifacts =
      (artifactArgs, artifactListFile, root).mapN {
        case (args, None, root) if args.isEmpty =>
          val artifacts = Files[IO]
            .walk(root)
            .evalFilter(path => Files[IO].isRegularFile(path))
          (root, artifacts)
        case (args, None, root) =>
          (root, Stream.emits(args))
        case (args, Some(file), root) =>
          val artifactsInFile =
            Files[IO].readUtf8Lines(file).filter(_.nonEmpty).map(Path(_))
          (root, Stream.emits(args) ++ artifactsInFile)
      }

    val commandOpts: Opts[IO[ExitCode]] = (rootAndArtifacts, out).mapN {
      case ((root, artifacts), output) =>
        val bytes = toArtifactInfoJson(root, artifacts)
        val write = output match {
          case Some(file) =>
            bytes.through(Files[IO].writeAll(file)).compile.drain.onError { _ =>
              Files[IO].deleteIfExists(file).void.voidError
            }
          case None => bytes.through(fs2.io.stdout[IO]).compile.drain
        }
        write *> IO.pure(ExitCode.Success)
    }

    val command = Command(
      name = "nix-coursier-cache",
      header = "Restore nix-friendly artifact information from coursier cache",
    )(commandOpts)
    command.parse(args) match {
      case Left(help) if help.errors.nonEmpty =>
        Console[IO].errorln(help.show) *> ExitCode.Error.pure[IO]
      case Left(help) =>
        Console[IO].println(help.show) *> ExitCode.Success.pure[IO]
      case Right(io: IO[ExitCode]) => io
    }
  }
}

def toArtifactInfoJson[F[_]: {Files, Processes, Concurrent}](
    root: Path,
    artifacts: Stream[F, Path],
): Stream[F, Byte] = artifacts
  .evalMap(path => ArtifactInfo.fromPath[F](root, path))
  .map(info => writeToString(info))
  .flatMap { infoJson =>
    Stream(infoJson, ",\n")
  }
  .dropLast
  .through(s => Stream("[\n") ++ s ++ Stream("\n]\n"))
  .through(fs2.text.utf8.encode)

final case class ArtifactInfo(
    url: String,
    path: String,
    hash: String,
)

object ArtifactInfo {
  given JsonValueCodec[ArtifactInfo] = JsonCodecMaker.make

  def fromPath[F[_]: {Files, Processes, Concurrent}](
      root: Path,
      artifact: Path,
  ): F[ArtifactInfo] = {
    for {
      _ <-
        if (!root.isAbsolute) {
          MonadThrow[F].raiseError(
            IllegalArgumentException(s"$root is not a absolute path")
          )
        } else ().pure[F]
      relpath <-
        if (artifact.isAbsolute) {
          if (artifact.startsWith(root)) {
            root.relativize(artifact).toString.pure[F]
          } else {
            MonadThrow[F].raiseError(
              IllegalArgumentException(s"$artifact does not start with $root")
            )
          }
        } else {
          artifact.toString.pure[F]
        }
      url <- relpathToUrl(relpath).pure[F].rethrow
      hash <- nixSRIHash(artifact)
    } yield ArtifactInfo(url, path = relpath, hash)
  }
}

def nixSRIHash[F[_]: {Concurrent, Processes}](path: Path): F[String] = {
  val base32 = ProcessBuilder(
    "nix-hash",
    "--type",
    "sha256",
    "--flat",
    "--base32",
    path.toString,
  ).spawn.use { p =>
    p.stdout.through(fs2.text.utf8.decode).compile.string.map(_.trim)
  }
  base32.flatMap { base32 =>
    ProcessBuilder("nix-hash", "--to-sri", "--type", "sha256", base32).spawn
      .use { p =>
        p.stdout.through(fs2.text.utf8.decode).compile.string.map(_.trim)
      }
  }
}

def relpathToUrl(path: String): Either[Exception, String] = {
  val firstSlash = path.indexOf('/')
  if (firstSlash == -1)
    return Left(IllegalArgumentException(s"illegal path: $path"))
  val (protocol, remaining) = path.splitAt(firstSlash)
  val unescaped =
    try {
      unescape(remaining.drop(1)) // drop the leading slash
    } catch {
      case e: IllegalArgumentException => return Left(e)
    }
  Right(s"$protocol://$unescaped")
}

/** left inverse of coursier.paths.CachePath.escape */
@throws[IllegalArgumentException]
def unescape(str: String): String = {
  def hexToDigit(c: Char): Int = c match {
    case c if c >= '0' && c <= '9' => c - '0'
    case c if c >= 'A' && c <= 'F' => c - 'A' + 10
    case _ => throw new IllegalArgumentException(s"Illegal char: $c")
  }

  val result = StringBuilder()
  val chars = str.toCharArray
  var i = 0
  while (i < chars.length) {
    chars(i) match {
      case '%' if i + 2 >= chars.length =>
        throw new IllegalArgumentException("Unexpected end of string")
      case '%' =>
        val code = hexToDigit(chars(i + 1)) * 16 + hexToDigit(chars(i + 2))
        result.append(code.asInstanceOf[Char])
        i += 3
      case _ =>
        result.append(chars(i))
        i += 1
    }
  }

  result.toString
}
