package org.adelheid.oneiro.gradle;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.jspecify.annotations.Nullable;

/**
 * Fetches runescape-api-stubs from GitHub over HTTPS, without git. The latest commit of the default branch comes from
 * the ref advertisement that {@code git ls-remote} reads too. A commit's files come from GitHub's zip archive of it,
 * unpacked into Gradle's cache once per commit.
 */
final class RemoteStubs {

	/** The stubs commit this release of Oneiro was tested against; another builds with a warning. */
	static final String TESTED_COMMIT = "8733037f8b0ae59b0da063ab1fecc60849b0cc98";
	static final String DEFAULT_REMOTE = "https://github.com/FriendlyDevelopment/runescape-api-stubs";

	private static final Pattern GITHUB = Pattern.compile("https://github\\.com/([\\w.-]+)/([\\w.-]+?)(?:\\.git)?/?");
	private static final Pattern SHA = Pattern.compile("[0-9a-f]{40}");
	private static final Duration TIMEOUT = Duration.ofSeconds(60);

	/** A repository on GitHub. */
	record Repository(String owner, String name) {

		static Repository parse(String url) {
			Matcher matcher = GITHUB.matcher(url.strip());
			if (!matcher.matches()) {
				throw new IllegalArgumentException("Oneiro fetches stubs only from GitHub,"
						+ " https://github.com/<owner>/<repository>, and " + url
						+ " is not such an address; use local(file(...)) for a checkout from anywhere else.");
			}
			return new Repository(matcher.group(1), matcher.group(2));
		}

		/** {@code owner/name}, as GitHub's addresses spell it. */
		String path() {
			return owner + "/" + name;
		}

		String folder() {
			return owner + "-" + name;
		}
	}

	private RemoteStubs() {
	}

	/** The latest commit of the repository's default branch. */
	static String latestCommit(Repository repository) throws IOException {
		URI refs = URI.create("https://github.com/" + repository.path() + ".git/info/refs?service=git-upload-pack");
		byte[] body = get(refs);
		// Packet lines: four hex digits of length, then the line; the first ref after the service line is HEAD
		int position = 0;
		while (position + 4 <= body.length) {
			int length = Integer.parseInt(new String(body, position, 4, StandardCharsets.US_ASCII), 16);
			if (length == 0) {
				position += 4;
				continue;
			}
			String line = new String(body, position + 4, length - 4, StandardCharsets.UTF_8);
			int end = line.indexOf('\0');
			String ref = end < 0 ? line.strip() : line.substring(0, end);
			if (ref.endsWith(" HEAD") && SHA.matcher(ref.substring(0, 40)).matches()) {
				return ref.substring(0, 40);
			}
			position += length;
		}
		throw new IOException("GitHub did not name the latest commit of " + repository.path()
				+ "; pin one: remote(\"<url>\", \"<commit>\").");
	}

	/** The commit's files in the cache, downloaded and unpacked the first time they are needed. */
	static Path checkout(Repository repository, String commit, Path cache) throws IOException {
		if (!SHA.matcher(commit).matches()) {
			throw new IOException("The stubs commit " + commit + " is not a full 40-character commit hash.");
		}
		Path target = cache.resolve(repository.folder()).resolve(commit);
		if (Files.isRegularFile(target.resolve(".complete"))) {
			return target;
		}
		byte[] archive = get(URI.create("https://codeload.github.com/" + repository.path() + "/zip/" + commit));
		Path unpacking = Files.createDirectories(cache.resolve(repository.folder())).resolve(commit + ".unpacking");
		delete(unpacking);
		try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
			ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null) {
				// The archive holds one folder named after the repository and commit; its contents are the checkout
				String name = entry.getName();
				int slash = name.indexOf('/');
				if (slash < 0 || slash == name.length() - 1) {
					continue;
				}
				Path file = unpacking.resolve(name.substring(slash + 1)).normalize();
				if (!file.startsWith(unpacking)) {
					throw new IOException("The stubs archive holds a path outside its folder: " + name);
				}
				if (entry.isDirectory()) {
					Files.createDirectories(file);
				} else {
					Files.createDirectories(file.getParent());
					Files.copy(zip, file, StandardCopyOption.REPLACE_EXISTING);
				}
			}
		}
		Files.writeString(unpacking.resolve(".complete"), commit + "\n");
		delete(target);
		Files.move(unpacking, target);
		return target;
	}

	/** The commit a local checkout is at, read from its {@code .git} folder; null when it is not a plain checkout. */
	static @Nullable String localCommit(Path checkout) {
		try {
			Path git = checkout.resolve(".git");
			if (!Files.isDirectory(git)) {
				return null;
			}
			String head = Files.readString(git.resolve("HEAD")).strip();
			if (SHA.matcher(head).matches()) {
				return head;
			}
			if (!head.startsWith("ref: ")) {
				return null;
			}
			String ref = head.substring("ref: ".length());
			Path loose = git.resolve(ref);
			if (Files.isRegularFile(loose)) {
				return Files.readString(loose).strip();
			}
			Path packed = git.resolve("packed-refs");
			if (Files.isRegularFile(packed)) {
				for (String line : Files.readAllLines(packed)) {
					if (line.endsWith(" " + ref)) {
						return line.substring(0, 40);
					}
				}
			}
			return null;
		} catch (IOException e) {
			return null;
		}
	}

	private static byte[] get(URI uri) throws IOException {
		HttpClient client = HttpClient.newBuilder()
				.followRedirects(HttpClient.Redirect.NORMAL)
				.connectTimeout(TIMEOUT)
				.build();
		HttpRequest request = HttpRequest.newBuilder(uri).timeout(TIMEOUT).header("User-Agent", "Oneiro").GET().build();
		try {
			HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
			try (InputStream body = response.body()) {
				if (response.statusCode() != 200) {
					throw new IOException(uri + " answered " + response.statusCode() + ".");
				}
				return body.readAllBytes();
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Fetching " + uri + " was interrupted.", e);
		}
	}

	private static void delete(Path folder) throws IOException {
		if (!Files.exists(folder)) {
			return;
		}
		try (Stream<Path> walk = Files.walk(folder)) {
			for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
				Files.delete(path);
			}
		}
	}
}
