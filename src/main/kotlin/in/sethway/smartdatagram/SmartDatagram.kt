package `in`.sethway.smartdatagram

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.*
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

class SmartDatagram(
  address: InetSocketAddress,
  private val bufferSize: Int = DEFAULT_BUFFER_SIZE
) :
  DatagramSocket(address) {

  companion object {
    const val DEFAULT_BUFFER_SIZE = 4096

    private val SIGNATURE = byteArrayOf(127, 2, 2, 127)
    private const val VERSION = 0.toByte()
  }

  private val executor = Executors.newSingleThreadExecutor()

  private class Route(
    val transformer: (ByteArray) -> ByteArray,
    val callback: ((InetAddress, Int, ByteArray, Boolean) -> Unit)
  )

  private val subscriptions = Collections.synchronizedMap(HashMap<String, Route>())
  private val packetFilter = TimeoutMap<String>(20 * 1000)

  init {
    trafficClass = 0x04 or 0x08 // Reliability + Speed
    receiveBufferSize = bufferSize
    soTimeout = 2048 // 2 secs

    executor.submit { beginReading() }
  }

  private fun beginReading() {
    val buffer = ByteArray(bufferSize)
    val packet = DatagramPacket(buffer, bufferSize)

    while (!Thread.currentThread().isInterrupted) {
      try {
        receive(packet)
      } catch (_: SocketTimeoutException) {
        continue
      }
      // Defining packet structure
      // [4 bytes] signature
      // [1 byte] version
      // [16 byte] unique packet ID
      // [n = 1 byte] route name length
      // [n bytes] route name
      // [k = 4 bytes] data length
      // [k bytes] packet data
      try {
        consumePacket(buffer, packet)
      } catch (t: Throwable) {
        println("Error consuming packet!")
        t.printStackTrace()
      }
    }
  }

  private fun consumePacket(buffer: ByteArray, packet: DatagramPacket) {
    var onset = 0
    val signature = buffer.copyOf(4)
    onset += 4
    if (!SIGNATURE.contentEquals(signature)) {
      println("Error: Wrong Smart UDP Signature!")
      return
    }
    val version = buffer[onset++]
    if (version != VERSION) {
      println("Error: Expected version $VERSION but got $version!")
      return
    }
    val uniquePacketId = String(buffer.copyOfRange(onset, onset + 16))
    onset += 16
    val consumed = packetFilter.containsKey(uniquePacketId)
    if (!consumed) {
      packetFilter[uniquePacketId] = System.currentTimeMillis()
    }
    val routeNameLength = buffer[onset++]
    val routeName = String(buffer.copyOfRange(onset, onset + routeNameLength))
    onset += routeNameLength

    val packetDataLength = ByteBuffer.wrap(buffer.copyOfRange(onset, onset + 4)).getInt()
    onset += 4
    val packetData = buffer.copyOfRange(onset, onset + packetDataLength)

    try {
      subscriptions[routeName]?.let {
        it.callback.invoke(packet.address, packet.port, it.transformer(packetData), consumed)
      }
    } catch (t: Throwable) {
      println("Error while dispatching subscription")
      t.printStackTrace()
    }
  }

  fun subscribe(
    routeName: String,
    transformer: ((ByteArray) -> ByteArray) = { it },
    callback: ((InetAddress, Int, ByteArray, Boolean) -> Unit)
  ) {
    subscriptions[routeName] = Route(transformer, callback)
  }

  class Packet(
    val address: InetAddress,
    val port: Int,
    val data: ByteArray,
  )

  fun expectPacket(routeName: String, timeout: Long): Packet? {
    val result = AtomicReference<Packet?>()
    subscribe(routeName) { address, port, data, consumed ->
      if (!consumed) {
        result.set(Packet(address, port, data))
      }
    }
    Thread.sleep(timeout)
    unsubscribe(routeName)
    return result.get()
  }

  fun unsubscribe(routeName: String) {
    subscriptions -= routeName
  }

  fun send(
    destinations: List<Destination>,
    routeName: String,
    data: ByteArray,
    exceptionHandler: (Exception) -> Unit = { throw it }
  ) {
    val bytes = preparePacket(routeName, data)
    for (destination in destinations) {
      try {
        send(DatagramPacket(bytes, 0, bytes.size, destination.address, destination.port))
      } catch (exception: IOException) {
        exceptionHandler(exception)
      }
    }
  }

  fun send(
    destination: Destination,
    routeName: String,
    data: ByteArray,
    exceptionHandler: (Exception) -> Unit = { throw it }
  ) {
    val bytes = preparePacket(routeName, data)
    try {
      send(DatagramPacket(bytes, 0, bytes.size, destination.address, destination.port))
    } catch (exception: IOException) {
      exceptionHandler(exception)
    }
  }

  private fun preparePacket(routeName: String, data: ByteArray): ByteArray {
    val baos = ByteArrayOutputStream()
    baos.write(SIGNATURE)
    baos.write(VERSION.toInt())
    baos.write(Random.nextBytes(16))

    val routeNameBytes = routeName.toByteArray()
    if (routeNameBytes.size > 256) {
      throw IOException("Route name cannot exceed 256 bytes")
    }
    baos.write(routeNameBytes.size)
    baos.write(routeNameBytes)

    baos.write(ByteBuffer.allocate(4).putInt(data.size).array())
    baos.write(data)

    return baos.toByteArray()
  }

  override fun close() {
    super.close()
    executor.shutdownNow()
  }

}