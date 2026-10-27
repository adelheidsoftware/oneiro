package org.adelheid.oneiro.gradle.remote;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.ValueSource;
import org.gradle.api.provider.ValueSourceParameters;
import org.jspecify.annotations.Nullable;

/**
 * The latest commit of a remote stubs repository's default branch, on GitHub, Codeberg or GitLab, looked up at most
 * once a day, as Gradle does for dynamic versions, and on every explicit {@code oneiroBindings} run. An
 * {@code --offline} build uses the commit it last looked up.
 */
public abstract class LatestStubsCommit implements ValueSource<String, LatestStubsCommit.Parameters> {

	private static final Logger LOGGER = Logging.getLogger(LatestStubsCommit.class);
	private static final Duration CHECK_EVERY = Duration.ofDays(1);

	/** What decides the lookup. */
	public interface Parameters extends ValueSourceParameters {

		Property<String> getRemote();

		DirectoryProperty getCache();

		Property<Boolean> getOffline();

		/** Whether to look the commit up now, however recently it was. */
		Property<Boolean> getRefresh();
	}

	@Override
	public String obtain() {
		Parameters parameters = getParameters();
		RemoteRepository repository = RemoteRepository.parse(parameters.getRemote().get());
		Path record = repository.folder(parameters.getCache().get().getAsFile().toPath()).resolve("latest");
		String[] known = read(record);
		if (parameters.getOffline().get()) {
			if (known == null) {
				String remote = parameters.getRemote().get();
				throw new GradleException("The build is offline and the latest stubs commit of " + remote
						+ " has never been fetched; build online once, or pin a commit.");
			}
			return known[1];
		}
		boolean recent = known != null
				&& Instant.ofEpochSecond(Long.parseLong(known[0])).plus(CHECK_EVERY).isAfter(Instant.now());
		if (known != null && recent && !parameters.getRefresh().get()) {
			return known[1];
		}
		try {
			String commit = repository.latestCommit();
			Files.createDirectories(record.getParent());
			Files.writeString(record, Instant.now().getEpochSecond() + " " + commit + "\n");
			return commit;
		} catch (IOException e) {
			if (known != null) {
				LOGGER.warn("Oneiro could not look up the latest stubs commit ({}); it uses {}, found earlier.",
						e.getMessage(), known[1]);
				return known[1];
			}
			throw new UncheckedIOException(e);
		}
	}

	/** The recorded lookup: when, in seconds since the epoch, and the commit; null when there is none. */
	private static String @Nullable [] read(Path record) {
		try {
			if (!Files.isRegularFile(record)) {
				return null;
			}
			String[] parts = Files.readString(record).strip().split(" ", 2);
			return parts.length == 2 ? parts : null;
		} catch (IOException e) {
			return null;
		}
	}
}
