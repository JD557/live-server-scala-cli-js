// write a basic http4s server

import io.circe.*
import io.circe.generic.auto.*
import io.circe.syntax.{*, given}

import cats.effect.*
import org.http4s.HttpRoutes
import org.http4s.dsl.io.*
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits.*
import org.http4s.server.Router
import com.comcast.ip4s.host
import com.comcast.ip4s.port
import com.comcast.ip4s.Port
import org.http4s.HttpApp
import org.http4s.server.staticcontent.*
import cats.effect.*

import cats.syntax.all.*

import scribe.Logging
import scribe.Level

import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.server.EmberServerBuilder

import scala.concurrent.duration.*
import org.http4s.Request
import cats.effect.std.*
import org.http4s.Http
import org.http4s.Response
import cats.data.OptionT
import cats.data.Kleisli
import org.http4s.scalatags.*
import fs2.*
import fs2.io.process.{Processes, ProcessBuilder}
import fs2.io.Watcher
import fs2.io.file.Files
import fs2.io.Watcher.Event
import org.http4s.ServerSentEvent
import _root_.io.circe.Encoder

import cats.syntax.strong
import fs2.concurrent.Topic
import scalatags.Text.styles
import cats.implicits.*
import com.monovore.decline.*
import com.monovore.decline.effect.*
import ProxyConfig.Equilibrium

sealed trait FrontendEvent derives Encoder.AsObject

case class KeepAlive() extends FrontendEvent derives Encoder.AsObject
case class PageRefresh() extends FrontendEvent derives Encoder.AsObject

def makeProxyConfig(frontendPort: Port, proxyTo: Port, matcher: String) = s"""
http:
  servers:
    - listen: $frontendPort
      serverNames:
        - localhost
      locations:
        - matcher: $matcher
          proxyPass: http://$$backend

  upstreams:
    - name: backend
      servers:
        - host: localhost
          port: $proxyTo
          weight: 5
"""

object LiveServer
    extends CommandIOApp(
      name = "LiveServer",
      header = "Scala JS live server",
      version = "0.0.1"
    ):

  private val refreshTopic = Topic[IO, String].toResource
  private val logger = scribe.cats[IO]
  // val logger = scribe.cats[IO]

  private def seedMapOnStart(stringPath: String, mr: MapRef[IO, String, Option[String]]) =
    val asFs2 = fs2.io.file.Path(stringPath)
    fs2.io.file
      .Files[IO]
      .walk(asFs2)
      .evalMap { f =>
        Files[IO]
          .isRegularFile(f)
          .ifM(
            logger.trace(s"hashing $f") >>
              fielHash(f).flatMap(h =>
                val key = asFs2.relativize(f)
                mr.setKeyValue(key.toString(), h)
              ),
            IO.unit
          )
      }
      .compile
      .drain
      .toResource

  end seedMapOnStart

  private def fileWatcher(
      stringPath: fs2.io.file.Path,
      mr: MapRef[IO, String, Option[String]]
  ): ResourceIO[IO[OutcomeIO[Unit]]] =
    fs2.Stream
      .resource(Watcher.default[IO].evalTap(_.watch(stringPath.toNioPath)))
      .flatMap { w =>
        w.events()
          .evalTap((e: Event) =>
            e match
              case Event.Created(path, i) =>
                // if path.endsWith(".js") then
                logger.trace(s"created $path, calculating hash") >>
                  fielHash(fs2.io.file.Path(path.toString()))
                    .flatMap(h =>
                      val serveAt = path.relativize(stringPath.toNioPath)
                      logger.trace(s"$serveAt :: hash -> $h") >>
                        mr.setKeyValue(serveAt.toString(), h)
                    )
              // else IO.unit
              case Event.Modified(path, i) =>
                // if path.endsWith(".js") then
                logger.trace(s"modified $path, calculating hash") >>
                  fielHash(fs2.io.file.Path(path.toString()))
                    .flatMap(h =>
                      val serveAt = path.relativize(stringPath.toNioPath)
                      logger.trace(s"$serveAt :: hash -> $h") >>
                        mr.setKeyValue(serveAt.toString(), h)
                    )
              // else IO.unit
              case Event.Deleted(path, i) =>
                val serveAt = path.relativize(stringPath.toNioPath)
                logger.trace(s"deleted $path, removing key") >>
                  mr.unsetKey(serveAt.toString())
              case e: Event.Overflow    => IO.println("overflow")
              case e: Event.NonStandard => IO.println("non-standard")
          )
      }
      .compile
      .drain
      .background
  end fileWatcher

  def routes(
      stringPath: String,
      refreshTopic: Topic[IO, String],
      stylesPath: Option[String],
      proxyRoutes: HttpRoutes[IO]
  ): Resource[IO, (HttpApp[IO], MapRef[IO, String, Option[String]], Ref[IO, Map[String, String]])] =
    Resource.eval(
      for
        ref <- Ref[IO].of(Map.empty[String, String])
        mr = MapRef.fromSingleImmutableMapRef[IO, String, String](ref)
      yield
        val staticFiles = Router(
          "" -> fileService[IO](FileService.Config(stringPath))
        )

        val styles =
          stylesPath.fold(HttpRoutes.empty[IO])(path =>
            Router(
              "" -> fileService[IO](FileService.Config(path))
            )
          )

        val overrides = HttpRoutes
          .of[IO] {
            case GET -> Root =>
              Ok(
                (ref.get
                  .map(_.toSeq.map((path, hash) => (fs2.io.file.Path(path), hash)))
                  .map(mods => makeHeader(mods, stylesPath.isDefined)))
              )
            case GET -> Root / "all" =>
              ref.get.flatMap { m =>
                Ok(m.toString)
              }
            case GET -> Root / "api" / "v1" / "sse" =>
              val keepAlive = fs2.Stream.fixedRate[IO](10.seconds).as(KeepAlive())
              Ok(
                keepAlive
                  .merge(refreshTopic.subscribe(10).as(PageRefresh()))
                  .map(msg => ServerSentEvent(Some(msg.asJson.noSpaces)))
              )
          }

        (overrides.combineK(staticFiles).combineK(styles).combineK(proxyRoutes).orNotFound, mr, ref)
    )
  end routes

  private def buildServer(httpApp: HttpApp[IO], port: Port) = EmberServerBuilder
    .default[IO]
    .withHttp2
    .withHost(host"localhost")
    .withPort(port)
    .withHttpApp(httpApp)
    .withShutdownTimeout(10.milli)
    .build

  // override def main: Opts[IO[ExitCode]] =
  //   (showProcessesOpts orElse buildOpts).map {
  //     case ShowProcesses(all)           => ???
  //     case BuildImage(dockerFile, path) => ???
  //   }

  val logLevelOpt: Opts[String] = Opts
    .option[String]("log-level", help = "The log level (e.g., info, debug, error)")
    .withDefault("info")
    .validate("Invalid log level") {
      case "info"  => true
      case "debug" => true
      case "error" => true
      case "warn"  => true
      case "trace" => true
      case _       => false
    }

  val baseDirOpt =
    Opts
      .option[String]("project-dir", "The fully qualified location of your project - e.g. c:/temp/helloScalaJS")
      .withDefault(os.pwd.toString())
      .validate("Must be a directory")(s => os.isDir(os.Path(s)))

  val outDirOpt = Opts
    .option[String]("out-dir", "Where the compiled JS will end up - e.g. c:/temp/helloScalaJS/.out")
    .withDefault((os.pwd / ".out").toString())
    .validate("Must be a directory")(s => os.isDir(os.Path(s)))

  val stylesDirOpt = Opts
    .option[String](
      "styles-dir",
      "A fully qualified path to your styles directory with LESS files in - e.g. c:/temp/helloScalaJS/styles"
    )
    .orNone
    .validate("Must be a directory")(sOpt => sOpt.fold(true)(s => os.isDir(os.Path(s))))

  val portOpt = Opts
    .option[Int]("port", "The port yo want to run the server on - e.g. 3000")
    .withDefault(3000)
    .validate("Port must be between 1 and 65535")(i => i > 0 && i < 65535)
    .map(i => Port.fromInt(i).get)

  val proxyPortTargetOpt = Opts
    .option[Int]("proxy-target-port", "The port you want to forward api requests to - e.g. 8080")
    .orNone
    .validate("Proxy Port must be between 1 and 65535")(iOpt => iOpt.fold(true)(i => i > 0 && i < 65535))
    .map(i => i.flatMap(Port.fromInt))

  val proxyPathMatchPrefix = Opts
    .option[String]("proxy-prefix-path", "Match routes starting with this prefix - e.g. /api")
    .orNone

  override def main: Opts[IO[ExitCode]] =

    given R: Random[IO] = Random.javaUtilConcurrentThreadLocalRandom[IO]

    (baseDirOpt, outDirOpt, stylesDirOpt, portOpt, proxyPortTargetOpt, proxyPathMatchPrefix, logLevelOpt).mapN {
      (baseDir, outDir, stylesDir, port, proxyTarget, pathPrefix, lvl) =>

        scribe.Logger.root
          .clearHandlers()
          .clearModifiers()
          .withHandler(minimumLevel = Some(Level.get(lvl).get))
          .replace()

        val proxyConfig = proxyTarget
          .zip(pathPrefix)
          .traverse { (pt, prfx) =>
            ProxyConfig.loadYaml[IO](makeProxyConfig(port, pt, prfx)).toResource
          }

        val server = for
          _ <- logger
            .debug(
              s"baseDir: $baseDir \n outDir: $outDir \n stylesDir: $stylesDir \n port: $port \n proxyTarget: $proxyTarget \n pathPrefix: $pathPrefix"
            )
            .toResource

          client <- EmberClientBuilder.default[IO].build

          proxyRoutes: HttpRoutes[IO] <- proxyConfig.flatMap {
            case Some(pc) =>
              (
                logger.debug("setup proxy server") >>
                  IO(HttpProxy.servers[IO](pc, client, pathPrefix.getOrElse(???)).head._2)
              ).toResource

            case None =>
              (
                logger.debug("no proxy set") >>
                  IO(HttpRoutes.empty[IO])
              ).toResource
          }
          // proxyRoutes: HttpRoutes[IO] = HttpProxy.servers(pc, client).head._2

          _ <- logger.info(s"Start dev server on https://localhost:$port").toResource

          refreshPub <- refreshTopic
          _ <- buildRunner(refreshPub, fs2.io.file.Path(baseDir), fs2.io.file.Path(outDir))(logger)
          routes <- routes(outDir.toString(), refreshPub, stylesDir, proxyRoutes)
          (app, mr, ref) = routes
          _ <- seedMapOnStart(outDir, mr)
          // _ <- stylesDir.fold(Resource.unit)(sd => seedMapOnStart(sd, mr))
          _ <- fileWatcher(fs2.io.file.Path(outDir), mr)
          // _ <- stylesDir.fold(Resource.unit[IO])(sd => fileWatcher(fs2.io.file.Path(sd), mr))
          server <- buildServer(app, port)
        yield server

        server.use(_ => IO.never).as(ExitCode.Success)
    }
  end main
end LiveServer
