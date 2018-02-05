import java.time.{Instant, LocalDateTime, ZonedDateTime}
import java.time.format.DateTimeFormatter

import cats.data.Kleisli
import cats.effect._
import cats.implicits._
import fs2._
import org.http4s.{Headers, HttpService, Request, Response}
import org.http4s.util.CaseInsensitiveString
import org.log4s.getLogger

import scala.concurrent.ExecutionContext

/**
  * Adapted from the Response logging middleware found in org.http4s.server.middleware
  */
object ResponseLogger {
  type Logformat[F[_]] = (Request[F], Response[F], Long) => String

  def CommonLogFormat[F[_]](userextractor: Request[F] => String = {_: Request[F] => "-"}): Logformat[F] = (req: Request[F], resp: Response[F], respsize: Long) =>  {
    val remote = req.remoteAddr.getOrElse("-")
    val ident = "-"
    val userid = userextractor(req)
    val date = DateTimeFormatter.ofPattern("dd/MMM/YYYY:HH:mm/ss ZZZZ").format(ZonedDateTime.now())
    val request = s"${req.method.name} ${req.pathInfo} ${req.httpVersion}"
    val code = resp.status.code
    val bytes = respsize
    s"""$remote $ident $userid [$date] "$request" $code $bytes"""
  }

  def CombinedLogFormat[F[_]](userextractor: Request[F] => String = {_: Request[F] => "-"}): Logformat[F] = (req: Request[F], resp: Response[F], respsize: Long) =>  {
    val remote = req.remoteAddr.getOrElse("-")
    val ident = "-"
    val userid = userextractor(req)
    val date = DateTimeFormatter.ofPattern("dd/MMM/YYYY:HH:mm/ss ZZZZ").format(ZonedDateTime.now())
    val request = s"${req.method.name} ${req.pathInfo} ${req.httpVersion}"
    val code = resp.status.code
    val bytes = respsize
    val referer = req.headers.get(CaseInsensitiveString("referer")).map("\""+_+"\"").getOrElse("-")
    val useragent = req.headers.get(CaseInsensitiveString("user-agent")).map("\""+_+"\"").getOrElse("-")
    s"""$remote $ident $userid [$date] "$request" $code $bytes $referer $useragent"""
  }

  private[this] val logger = getLogger

  def apply[F[_]](
                 formatter: Logformat[F], sink: Kleisli[F, String, Unit]
                 )(service: HttpService[F])(
                   implicit F: Effect[F],
                   ec: ExecutionContext = ExecutionContext.global): HttpService[F] =
    Kleisli { req =>
      service(req).semiflatMap { response =>
          async.refOf[F, Vector[Segment[Byte, Unit]]](Vector.empty[Segment[Byte, Unit]]).map {
            vec =>
              val newBody = Stream
                .eval(vec.get)
                .flatMap(v => Stream.emits(v).covary[F])
                .flatMap(c => Stream.segment(c).covary[F])

              response.copy(
                body = response.body
                  // Cannot Be Done Asynchronously - Otherwise All Chunks May Not Be Appended Previous to Finalization
                  .observe(_.segments.flatMap(s => Stream.eval_(vec.modify(_ :+ s))))
                  .onFinalize {
                    newBody.compile.toVector.flatMap { bytes =>
                      sink(formatter.apply(req, response, bytes.size))
                    }
                  }
              )
          }
      }
    }

}
