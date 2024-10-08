/*
 * Copyright 2021 - 2023 Sporta Technologies PVT LTD & the ZIO HTTP contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package zio.http.netty

import zio._
import zio.stacktracer.TracingImplicits.disableAutoTrace

import zio.http._
import zio.http.netty.model.Conversions

import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http._

private[zio] object NettyResponseEncoder {
  private val dateHeaderCache = CachedDateHeader.default

  def encode(response: Response)(implicit unsafe: Unsafe): HttpResponse = {
    val body = response.body
    if (body.isComplete) {
      assert(body.isInstanceOf[Body.UnsafeBytes], "expected completed body to implement UnsafeBytes")
      fastEncode(response, body.asInstanceOf[Body.UnsafeBytes].unsafeAsArray)
    } else {
      val status   = response.status
      val jHeaders = Conversions.headersToNetty(response.headers)
      val jStatus  = Conversions.statusToNetty(status)
      maybeAddDateHeader(jHeaders, status)

      response.body.knownContentLength match {
        case Some(contentLength)                                    =>
          jHeaders.set(HttpHeaderNames.CONTENT_LENGTH, contentLength)
        case _ if jHeaders.contains(HttpHeaderNames.CONTENT_LENGTH) =>
          ()
        case _                                                      =>
          jHeaders.set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED)
      }

      new DefaultHttpResponse(HttpVersion.HTTP_1_1, jStatus, jHeaders)
    }
  }

  def fastEncode(response: Response, bytes: Array[Byte])(implicit unsafe: Unsafe): FullHttpResponse = {
    if (response.encoded eq null) {
      response.encoded = doEncode(response, bytes)
    }
    response.encoded.asInstanceOf[FullHttpResponse]
  }

  private def doEncode(response: Response, bytes: Array[Byte]): FullHttpResponse = {
    val jHeaders = Conversions.headersToNetty(response.headers)
    val status   = response.status
    maybeAddDateHeader(jHeaders, status)

    val jStatus = Conversions.statusToNetty(status)

    val jContent = Unpooled.wrappedBuffer(bytes)

    // The content-length MUST match the length of the content we are sending, so we ignore any user-provided value
    jHeaders.set(HttpHeaderNames.CONTENT_LENGTH, bytes.length)

    new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, jStatus, jContent, jHeaders, EmptyHttpHeaders.INSTANCE)
  }

  /**
   * We don't need to add the Date header in the following case:
   *   - Status code is 1xx
   *   - Status code is 5xx
   *   - User already provided it
   */
  private def maybeAddDateHeader(headers: HttpHeaders, status: Status): Unit = {
    if (status.isInformational || status.isServerError || headers.contains(HttpHeaderNames.DATE)) ()
    else {
      val _ = headers.set(HttpHeaderNames.DATE, dateHeaderCache.get())
    }
  }

}
