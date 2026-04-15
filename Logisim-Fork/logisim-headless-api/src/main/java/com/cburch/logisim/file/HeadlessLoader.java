package com.cburch.logisim.file;

import com.cburch.logisim.plugin.PluginLoader;
import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import javax.swing.filechooser.FileFilter;

public class HeadlessLoader extends Loader {
	public HeadlessLoader() {
		super(null);
	}

	@Override
	File getFileFor(String name, FileFilter filter) {
		String resolvedName = PluginLoader.check(name);
		if (resolvedName != null) {
			name = resolvedName;
		}

		File requested = new File(name);
		if (!requested.isAbsolute()) {
			File currentDirectory = getCurrentDirectory();
			if (currentDirectory != null) {
				requested = new File(currentDirectory, name);
			}
		}

		if (requested.canRead()) {
			return requested;
		}

		File fallback = findBestMatch(requested, filter);
		if (fallback != null) {
			return fallback;
		}

		throw new LoaderException("Missing library file: " + requested.getPath());
	}

	@Override
	public void showError(String description) {
		throw new LoaderException(description, true);
	}

	private File findBestMatch(File requested, FileFilter filter) {
		File rootDirectory = resolveSearchRoot(requested);
		if (rootDirectory == null || !rootDirectory.isDirectory()) {
			return null;
		}

		List<File> candidates = collectCandidateFiles(rootDirectory, filter);
		if (candidates.isEmpty()) {
			return null;
		}

		String requestedBaseRaw = stripExtension(requested.getName());
		String requestedBase = normalizeBaseName(requested.getName());
		int bestScore = Integer.MIN_VALUE;
		File bestMatch = null;

		for (File candidate : candidates) {
			if (!containsCompleteName(candidate.getName(), requestedBaseRaw, requestedBase)) {
				continue;
			}

			int score = scoreCandidate(rootDirectory, requestedBaseRaw, requestedBase, candidate);
			if (score > bestScore) {
				bestScore = score;
				bestMatch = candidate;
			}
		}

		return bestMatch;
	}

	private File resolveSearchRoot(File requested) {
		File currentDirectory = getCurrentDirectory();
		if (currentDirectory != null && currentDirectory.isDirectory()) {
			return currentDirectory;
		}

		File requestedParent = requested.getParentFile();
		if (requestedParent != null && requestedParent.isDirectory()) {
			return requestedParent;
		}

		return null;
	}

	private List<File> collectCandidateFiles(File rootDirectory, FileFilter filter) {
		List<File> result = new ArrayList<>();
		Deque<File> queue = new ArrayDeque<>();
		queue.add(rootDirectory);

		while (!queue.isEmpty()) {
			File current = queue.removeFirst();
			File[] children = current.listFiles();
			if (children == null) {
				continue;
			}

			for (File child : children) {
				if (child == null) {
					continue;
				}
				if (child.isDirectory()) {
					queue.addLast(child);
					continue;
				}
				if (!child.isFile() || !child.canRead()) {
					continue;
				}
				if (filter != null && !filter.accept(child)) {
					continue;
				}
				result.add(child);
			}
		}

		return result;
	}

	private boolean containsCompleteName(
		String candidateName, String requestedBaseRaw, String requestedBase) {
		String candidateBaseRaw = stripExtension(candidateName);
		if (containsIgnoreCase(candidateBaseRaw, requestedBaseRaw)) {
			return true;
		}
		String candidateBase = normalizeBaseName(candidateName);
		return !requestedBase.isEmpty() && candidateBase.contains(requestedBase);
	}

	private int scoreCandidate(
		File rootDirectory, String requestedBaseRaw, String requestedBase, File candidate) {
		String candidateName = candidate.getName();
		String candidateBaseRaw = stripExtension(candidateName);
		String candidateBase = normalizeBaseName(candidateName);

		int score = 0;
		if (candidateBaseRaw.equalsIgnoreCase(requestedBaseRaw)) {
			score += 10_000;
		}
		if (candidateBase.equals(requestedBase)) {
			score += 5_000;
		}
		if (containsIgnoreCase(candidateBaseRaw, requestedBaseRaw)) {
			score += 2_000;
		}
		if (!requestedBase.isEmpty() && candidateBase.contains(requestedBase)) {
			score += 1_000;
		}

		int depthPenalty = pathDepth(rootDirectory, candidate) * 200;
		score -= depthPenalty;

		int rawDelta = Math.abs(candidateBaseRaw.length() - requestedBaseRaw.length());
		score -= rawDelta * 10;
		int normalizedDelta = Math.abs(candidateBase.length() - requestedBase.length());
		score -= normalizedDelta * 5;

		if (!requestedBase.isEmpty()) {
			score += commonPrefixLength(requestedBase, candidateBase) * 20;
			score += longestCommonSubsequenceLength(requestedBase, candidateBase) * 8;
		}
		return score;
	}

	private boolean containsIgnoreCase(String source, String expected) {
		if (source == null || expected == null || expected.isEmpty()) {
			return false;
		}
		return source.toLowerCase().contains(expected.toLowerCase());
	}

	private String stripExtension(String fileName) {
		int extensionIndex = fileName.lastIndexOf('.');
		return extensionIndex > 0 ? fileName.substring(0, extensionIndex) : fileName;
	}

	private String normalizeBaseName(String fileName) {
		int extensionIndex = fileName.lastIndexOf('.');
		String baseName = extensionIndex > 0 ? fileName.substring(0, extensionIndex) : fileName;
		return baseName.toLowerCase().replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}]", "");
	}

	private int commonPrefixLength(String left, String right) {
		int max = Math.min(left.length(), right.length());
		int length = 0;
		while (length < max && left.charAt(length) == right.charAt(length)) {
			length++;
		}
		return length;
	}

	private int longestCommonSubsequenceLength(String left, String right) {
		int[][] dp = new int[left.length() + 1][right.length() + 1];
		for (int i = 1; i <= left.length(); i++) {
			for (int j = 1; j <= right.length(); j++) {
				if (left.charAt(i - 1) == right.charAt(j - 1)) {
					dp[i][j] = dp[i - 1][j - 1] + 1;
				} else {
					dp[i][j] = Math.max(dp[i - 1][j], dp[i][j - 1]);
				}
			}
		}
		return dp[left.length()][right.length()];
	}

	private int pathDepth(File rootDirectory, File candidate) {
		File current = candidate.getParentFile();
		int depth = 0;
		while (current != null && !current.equals(rootDirectory)) {
			depth++;
			current = current.getParentFile();
		}
		return depth;
	}
}