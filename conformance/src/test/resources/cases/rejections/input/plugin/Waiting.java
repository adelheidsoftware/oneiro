package plugin;

import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Ways a Java program waits or tells the time, which the client has nothing for. */
public final class Waiting {

	private Waiting() {
	}

	static long started() {
		return System.currentTimeMillis();
	}

	static long now() {
		return Instant.now().toEpochMilli();
	}

	static void later(Runnable work) {
		new Thread(work).start();
	}

	static void every(Runnable work) {
		ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
		executor.scheduleAtFixedRate(work, 0, 1, TimeUnit.SECONDS);
	}
}
