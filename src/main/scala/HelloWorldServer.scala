package net.andimiller.http4s.logger

import cats.data.Kleisli
import cats.effect.IO
import fs2.StreamApp
import org.http4s._
import org.http4s.dsl.Http4sDsl
import org.http4s.server.blaze.BlazeBuilder

import scala.concurrent.ExecutionContext.Implicits.global

object HelloWorldServer extends StreamApp[IO] with Http4sDsl[IO] {
  val service = HttpService[IO] {
    case GET -> Root / "hello" / name =>
      Ok(s"hello $name")
  }

  val stdout = Kleisli { s: String => IO { println(s) }}

  def stream(args: List[String], requestShutdown: IO[Unit]) =
    BlazeBuilder[IO]
      .bindHttp(8080, "0.0.0.0")
      .mountService(ResponseLoggers(ResponseLoggers.JsonLogFormat[IO](), stdout)(service), "/")
      .serve
}
