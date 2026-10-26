package org.adelheid.oneiro.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The repositories Oneiro fetches, by their web addresses: where each host advertises a repository's refs, where it
 * serves a commit's archive, and the addresses it does not take. Nothing here goes to the network.
 */
class RemoteRepositoryTest {

	@Test
	void aGitHubRepository() {
		RemoteRepository repository = RemoteRepository.parse("https://github.com/adelheidsoftware/rs3-api-types");
		assertEquals(new RemoteRepository(RemoteRepository.Host.GITHUB, "adelheidsoftware/rs3-api-types"),
				repository);
		assertEquals("https://github.com/adelheidsoftware/rs3-api-types.git/info/refs?service=git-upload-pack",
				repository.refs().toString());
		assertEquals("https://codeload.github.com/adelheidsoftware/rs3-api-types/zip/" + commit(),
				repository.archive(commit()).toString());
		assertEquals(Path.of("cache", "github.com", "adelheidsoftware", "rs3-api-types"),
				repository.folder(Path.of("cache")));
	}

	@Test
	void aCodebergRepository() {
		RemoteRepository repository =
				RemoteRepository.parse("https://codeberg.org/ZyklopMarco/runescape-api-zyklib.git");
		assertEquals(new RemoteRepository(RemoteRepository.Host.CODEBERG, "ZyklopMarco/runescape-api-zyklib"),
				repository);
		assertEquals("https://codeberg.org/ZyklopMarco/runescape-api-zyklib.git/info/refs?service=git-upload-pack",
				repository.refs().toString());
		assertEquals("https://codeberg.org/ZyklopMarco/runescape-api-zyklib/archive/" + commit() + ".zip",
				repository.archive(commit()).toString());
	}

	/** GitLab's groups nest, so a project's path has two names or more. */
	@Test
	void aGitLabProjectInASubgroup() {
		RemoteRepository repository = RemoteRepository.parse("https://gitlab.com/herbs/lua/types/");
		assertEquals(new RemoteRepository(RemoteRepository.Host.GITLAB, "herbs/lua/types"), repository);
		assertEquals("https://gitlab.com/herbs/lua/types.git/info/refs?service=git-upload-pack",
				repository.refs().toString());
		assertEquals("https://gitlab.com/herbs/lua/types/-/archive/" + commit() + "/types-" + commit() + ".zip",
				repository.archive(commit()).toString());
	}

	@Test
	void anotherHostIsRefused() {
		IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
				() -> RemoteRepository.parse("https://bitbucket.org/herbs/types"));
		String message = String.valueOf(refused.getMessage());
		assertTrue(message.startsWith("https://bitbucket.org/herbs/types is not the address of a repository Oneiro can"
				+ " fetch: it fetches from GitHub, https://github.com/<owner>/<repository>, Codeberg,"), message);
		assertThrows(IllegalArgumentException.class, () -> RemoteRepository.parse("http://github.com/herbs/types"));
	}

	/** A page of a repository, rather than the repository's own address, is not taken for another repository. */
	@Test
	void aPageOfARepositoryIsRefused() {
		IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
				() -> RemoteRepository.parse("https://github.com/herbs/types/tree/main"));
		assertEquals("https://github.com/herbs/types/tree/main is not the address of a repository on GitHub: give the"
				+ " repository's own page, https://github.com/<owner>/<repository>.", refused.getMessage());
		assertThrows(IllegalArgumentException.class,
				() -> RemoteRepository.parse("https://gitlab.com/herbs/types/-/tree"));
		assertThrows(IllegalArgumentException.class, () -> RemoteRepository.parse("https://codeberg.org/herbs"));
	}

	private static String commit() {
		return "22dcf36a36db1879798d96eec1a5528cc996b168";
	}
}
