package `in`.sethway.smartdatagram

import java.net.InetAddress
import java.net.InetSocketAddress

object SimpleTestB {
  @JvmStatic
  fun main(args: Array<String>) {
    val datagram = SmartDatagram(InetSocketAddress("0.0.0.0", 0))
    datagram.send(Destination(InetAddress.getByName("0.0.0.0"), 8766), "hello", "maow".toByteArray())
  }
}