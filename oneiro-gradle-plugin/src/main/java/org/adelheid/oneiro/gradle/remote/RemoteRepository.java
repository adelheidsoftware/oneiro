package org.adelheid.oneiro.gradle.remote;

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
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.jspecify.annotations.Nullable;

/**
 * A git repository on GitHub, Codeberg or GitLab, fetched over HTTPS without git: the latest commit of its default
 * branch from the ref advertisement that {@code git ls-remote} reads too, which the three hosts serve alike, and a
 * commit's files from the host's zip archive of it, unpacked into Gradle's cache once per commit.
 *
 * @param path the repository's path on its host: {@code owner/name}, or on GitLab, where groups nest,
 *     {@code group/subgroup/name}
 */
public record RemoteRepository(Host host, String path) {

	/** The hosts Oneiro fetches from, each with its address and the form of its archives. */
	enum Host {
		GITHUB("GitHub", "github.com"),
		CODEBERG("Codeberg", "codeberg.org"),
		GITLAB("GitLab", "gitlab.com");

		final String displayName;
		final String domain;

		Host(String displayName, String domain) {
			this.displayName = displayName;
			this.domain = domain;
		}
	}

	private static final Pattern URL = Pattern.compile("https://([\\w.-]+)/([\\w./-]+?)(?:\\.git)?/?");
	private static final Pattern NAME = Pattern.compile("[\\w.-]+");
	private static final Pattern SHA = Pattern.compile("[0-9a-f]{40}");
	private static final Duration TIMEOUT = Duration.ofSeconds(60);

	/**
	 * The repository a web address names: {@code https://github.com/<owner>/<name>},
	 * {@code https://codeberg.org/<owner>/<name>} or {@code https://gitlab.com/<group>/<name>}, with or without
	 * {@code .git}.
	 */
	public static RemoteRepository parse(String url) {
		Matcher matcher = URL.matcher(url.strip());
		Host host = matcher.matches() ? host(matcher.group(1)) : null;
		if (host == null) {
			throw new IllegalArgumentException(url + " is not the address of a repository Oneiro can fetch: it fetches"
					+ " from GitHub, https://github.com/<owner>/<repository>, Codeberg,"
					+ " https://codeberg.org/<owner>/<repository>, and GitLab, https://gitlab.com/<group>/<project>;"
					+ " for a repository anywhere else, use a checkout on this machine.");
		}
		List<String> segments = List.of(matcher.group(2).split("/", -1));
		// GitLab's groups nest; on the other hosts, a repository is an owner's
		boolean shaped = host == Host.GITLAB ? segments.size() >= 2 : segments.size() == 2;
		// A name of GitLab's web pages, /-/tree/main, is no group's
		boolean named = segments.stream().allMatch(segment -> NAME.matcher(segment).matches() && !segment.equals("-"));
		if (!shaped || !named) {
			throw new IllegalArgumentException(url + " is not the address of a repository on " + host.displayName
					+ ": give the repository's own page, https://" + host.domain + "/"
					+ (host == Host.GITLAB ? "<group>/<project>" : "<owner>/<repository>") + ".");
		}
		return new RemoteRepository(host, matcher.group(2));
	}

	private static @Nullable Host host(String domain) {
		for (Host host : Host.values()) {
			if (host.domain.equals(domain.toLowerCase(Locale.ROOT))) {
				return host;
			}
		}
		return null;
	}

	/** The repository's web address. */
	public String url() {
		return "https://" + host.domain + "/" + path;
	}

	/** Where the host advertises the repository's refs, as it does for {@code git ls-remote}. */
	URI refs() {
		return URI.create(url() + ".git/info/refs?service=git-upload-pack");
	}

	/** Where the host serves the zip archive of a commit's files, one folder that holds them. */
	URI archive(String commit) {
		String name = path.substring(path.lastIndexOf('/') + 1);
		return URI.create(switch (host) {
			case GITHUB -> "https://codeload.github.com/" + path + "/zip/" + commit;
			case CODEBERG -> url() + "/archive/" + commit + ".zip";
			case GITLAB -> url() + "/-/archive/" + commit + "/" + name + "-" + commit + ".zip";
		});
	}

	/** The folder of the repository's files in the cache, one folder per commit inside it. */
	Path folder(Path cache) {
		return cache.resolve(host.domain).resolve(path);
	}

	/** The latest commit of the repository's default branch. */
	String latestCommit() throws IOException {
		byte[] body = get(refs(), "There is no public repository at " + url()
				+ "; check the address, and that the repository is public.");
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
		throw new IOException(host.displayName + " did not name the latest commit of " + url()
				+ "; pin one: remote(\"" + url() + "\", \"<commit>\").");
	}

	/** The commit's files in the cache, downloaded and unpacked the first time they are needed. */
	public Path checkout(String commit, Path cache) throws IOException {
		if (!SHA.matcher(commit).matches()) {
			throw new IOException("The commit " + commit + " of " + url() + " is not a full 40-character commit hash.");
		}
		Path folder = folder(cache);
		Path target = folder.resolve(commit);
		if (Files.isRegularFile(target.resolve(".complete"))) {
			return target;
		}
		byte[] archive = get(archive(commit), url() + " has no commit " + commit
				+ " that Oneiro can download; check the commit, and that the repository is public.");
		Path unpacking = Files.createDirectories(folder).resolve(commit + ".unpacking");
		delete(unpacking);
		try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
			ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null) {
				// The archive holds one folder, named after the repository and commit; its contents are the checkout
				String name = entry.getName();
				int slash = name.indexOf('/');
				if (slash < 0 || slash == name.length() - 1) {
					continue;
				}
				Path file = unpacking.resolve(name.substring(slash + 1)).normalize();
				if (!file.startsWith(unpacking)) {
					throw new IOException("The archive of " + url() + " holds a path outside its folder: " + name);
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
	public static @Nullable String localCommit(Path checkout) {
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

	/**
	 * The body of a page.
	 *
	 * @param missing what the error says when the host has no such page, or keeps it from the public: the hosts answer
	 *     401 for a repository that does not exist, so that a private one cannot be told from it
	 */
	private static byte[] get(URI uri, String missing) throws IOException {
		HttpClient client = HttpClient.newBuilder()
				.followRedirects(HttpClient.Redirect.NORMAL)
				.connectTimeout(TIMEOUT)
				.build();
		HttpRequest request = HttpRequest.newBuilder(uri).timeout(TIMEOUT).header("User-Agent", "Oneiro").GET().build();
		try {
			HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
			try (InputStream body = response.body()) {
				int status = response.statusCode();
				if (status == 401 || status == 403 || status == 404) {
					throw new IOException(missing);
				}
				if (status != 200) {
					throw new IOException(uri + " answered " + status + ".");
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
