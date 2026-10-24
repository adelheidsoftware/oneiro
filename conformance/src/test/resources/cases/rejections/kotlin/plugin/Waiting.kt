package plugin

import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Ways a Kotlin program waits or tells the time, which the client has nothing for. */
object Waiting {

	fun started(): Long {
		return System.currentTimeMillis()
	}

	fun now(): Long {
		return Instant.now().toEpochMilli()
	}

	fun later(work: Runnable) {
		Thread(work).start()
	}

	fun every(work: Runnable) {
		val executor = Executors.newSingleThreadScheduledExecutor()
		executor.scheduleAtFixedRate(work, 0, 1, TimeUnit.SECONDS)
	}
}
