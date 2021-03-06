// Copyright: 2017 https://github.com/fommil/drone-dynamic-agents/graphs
// License: http://www.apache.org/licenses/LICENSE-2.0
package http

/**
 * An algebra (and FS2 interpreter) for issuing basic GET / POST
 * requests to a web server that returns JSON. Uses the spinoco HTTP
 * protocol definition classes out of convenience.
 */
package client

import scala.collection.immutable.{ List, Nil }
import scala.language.higherKinds

import io.circe.Decoder
import freestyle._
import spinoco.protocol.http._
import spinoco.protocol.http.header._
import http.encoding._

object algebra {
  final case class Response[T](header: HttpResponseHeader, body: T)

  @free trait JsonHttpClient {
    def get[B](
      uri: Uri,
      headers: List[HttpHeader] = Nil
    )(
      implicit decoder: Decoder[B]
    ): FS[Response[B]]

    // using application/x-www-form-urlencoded
    def postUrlencoded[A, B](
      uri: Uri,
      payload: A,
      headers: List[HttpHeader] = Nil
    )(implicit encoder: UrlEncoded[A], decoder: Decoder[B]): FS[Response[B]]
  }
}
