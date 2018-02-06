package net.andimiller.http4s.logger

import java.time.{Instant, LocalDateTime, ZonedDateTime}
import java.time.format.DateTimeFormatter

import io.circe.Json
import io.circe.syntax._
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
object ResponseLoggers {
  type Logformat[F[_]] = (Request[F], Response[F], Long) => String

  def CommonLogFormat[F[_]](vhost: Boolean = false, combined: Boolean = false, userextractor: Request[F] => String = { _: Request[F] =>
    "-"
  }): Logformat[F] =
    (req: Request[F], resp: Response[F], respsize: Long) => {
      val remote = req.remoteAddr.getOrElse("-")
      val ident  = "-"
      val userid = userextractor(req)
      val date = DateTimeFormatter
        .ofPattern("dd/MMM/YYYY:HH:mm:ss XXXX")
        .format(ZonedDateTime.now())
      val request = s"${req.method.name} ${req.pathInfo} ${req.httpVersion}"
      val code    = resp.status.code
      val bytes   = respsize
      val referer =
        req.headers.get(CaseInsensitiveString("referer")).getOrElse("-")
      val useragent =
        req.headers.get(CaseInsensitiveString("user-agent")).getOrElse("-")
      val virtualhost: String = req.serverAddr

      // build it up
      s"""${if (vhost) virtualhost + " " else ""}$remote $ident $userid [$date] "$request" $code $bytes""" + {
        if (combined) s""" "$referer" "$useragent"""" else ""
      }
    }

  def CombinedLogFormat[F[_]](vhost: Boolean = false, userextractor: Request[F] => String = { _: Request[F] =>
    "-"
  }): Logformat[F] =
    CommonLogFormat[F](vhost, combined = true, userextractor = userextractor)

  def JsonLogFormat[F[_]](): Logformat[F] =
    (req: Request[F], resp: Response[F], repsize: Long) => {
      // request
      val reqStats = List(
        req.remoteAddr.fold(Json.obj()) { s =>
          Json.obj("remote" -> Json.fromString(s))
        },
        Json.obj(
          "date"    -> Json.fromString(DateTimeFormatter.ISO_DATE_TIME.format(ZonedDateTime.now())),
          "verb"    -> Json.fromString(req.method.name),
          "path"    -> Json.fromString(req.pathInfo),
          "headers" -> Json.fromFields(req.headers.toList.map(h => (h.name.value, Json.fromString(h.value)))),
          "server"  -> Json.fromString(req.serverAddr)
        )
      ).reduce(_.deepMerge(_))
      // response
      val respStats = Json.obj(
        "code"    -> Json.fromInt(resp.status.code),
        "reason"  -> Json.fromString(resp.status.reason),
        "headers" -> Json.fromFields(resp.headers.toList.map(h => (h.name.value, Json.fromString(h.value)))),
        "size"    -> Json.fromLong(repsize)
      )
      Json
        .obj(
          "request"  -> reqStats,
          "response" -> respStats
        )
        .noSpaces
    }

  private[this] val logger = getLogger

  def apply[F[_]](
      formatter: Logformat[F],
      sink: Kleisli[F, String, Unit]
  )(service: HttpService[F])(implicit F: Effect[F], ec: ExecutionContext = ExecutionContext.global): HttpService[F] =
    Kleisli { req =>
      service(req).semiflatMap { response =>
        async
          .refOf[F, Vector[Segment[Byte, Unit]]](Vector.empty[Segment[Byte, Unit]])
          .map { vec =>
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
