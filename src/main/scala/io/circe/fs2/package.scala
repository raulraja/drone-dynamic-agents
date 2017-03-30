// Copyright: 2017 https://github.com/fommil/drone-dynamic-agents/graphs
// License: http://www.apache.org/licenses/LICENSE-2.0
package io.circe

import _root_.fs2.{Chunk, Pipe, Stream}
import _root_.jawn.{AsyncParser, ParseException}
import io.circe.jawn.CirceSupportParser
import scala.collection.Seq

// Copyright (C) 2017 n4to4
// WORKAROUND until released: https://github.com/circe/circe/pull/554
package object fs2 {
  final def stringParser[F[_]]: Pipe[F, String, Json] = new ParsingPipe[F, String] {
    protected[this] final def parseWith(p: AsyncParser[Json])(in: String): Either[ParseException, Seq[Json]] =
      p.absorb(in)(CirceSupportParser.facade)
  }

  final def byteParser[F[_]]: Pipe[F, Chunk[Byte], Json] = new ParsingPipe[F, Chunk[Byte]] {
    protected[this] final def parseWith(p: AsyncParser[Json])(in: Chunk[Byte]): Either[ParseException, Seq[Json]] =
      p.absorb(in.toArray)(CirceSupportParser.facade)
  }

  final def decoder[F[_], A](implicit decode: Decoder[A]): Pipe[F, Json, A] =
    _.flatMap { json =>
      decode(json.hcursor) match {
        case Left(df) => Stream.fail(df)
        case Right(a) => Stream.emit(a)
      }
    }
}