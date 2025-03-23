package `in`.sethway.smartdatagram

import java.net.InetSocketAddress

object SimpleTest {
  @JvmStatic
  fun main(args: Array<String>) {
    val datagram = SmartDatagram(InetSocketAddress("::", 8766))
    datagram.subscribe("hello") { address, port, bytes ->
      println("Packet: $address, $port, ${String(bytes)}")
    }
  }
}