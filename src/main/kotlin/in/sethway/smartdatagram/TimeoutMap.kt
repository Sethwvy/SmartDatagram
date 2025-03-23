package `in`.sethway.smartdatagram

class TimeoutMap<E>(private val ttl: Long) : LinkedHashMap<E, Long>() {
  override fun removeEldestEntry(eldest: Map.Entry<E, Long>): Boolean {
    return System.currentTimeMillis() - eldest.value > ttl
  }
}