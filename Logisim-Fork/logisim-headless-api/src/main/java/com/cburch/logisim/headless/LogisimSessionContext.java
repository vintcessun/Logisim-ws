package com.cburch.logisim.headless;

import com.cburch.hex.HexModel;
import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.CircuitState;
import com.cburch.logisim.circuit.Propagator;
import com.cburch.logisim.circuit.Simulator;
import com.cburch.logisim.circuit.SimulatorEvent;
import com.cburch.logisim.circuit.SimulatorListener;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.data.BitWidth;
import com.cburch.logisim.data.Bounds;
import com.cburch.logisim.data.Value;
import com.cburch.logisim.file.HeadlessLoader;
import com.cburch.logisim.file.LoadFailedException;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.gui.hex.HexFile;
import com.cburch.logisim.instance.Instance;
import com.cburch.logisim.instance.InstanceState;
import com.cburch.logisim.instance.StdAttr;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.std.base.Text;
import com.cburch.logisim.std.wiring.Pin;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.ImageIO;

/**
 * Manages the state and operations for a single Logisim session.
 */
public class LogisimSessionContext implements AutoCloseable {
	private static final long TEXT_ALIAS_MAX_DISTANCE_SQ = 250L * 250L;
	private static final long TEXT_ALIAS_MIN_GAP_SQ = 40L * 40L;

	private Project project;
	private LogisimFile logisimFile;
	private HeadlessCanvas canvas;
	private Map<String, List<Component>> componentCache = new ConcurrentHashMap<>();
	private Map<String, List<Component>> inputComponentCache = new ConcurrentHashMap<>();
	private Map<String, List<Component>> outputComponentCache = new ConcurrentHashMap<>();
	private Map<String, Component> componentIdCache = new ConcurrentHashMap<>();
	private Map<Component, String> reverseComponentIdCache = new ConcurrentHashMap<>();

	private final Object stabilityLock = new Object();
	private final StabilityListener stabilityListener = new StabilityListener();

	private class StabilityListener implements SimulatorListener {
		@Override
		public void propagationCompleted(SimulatorEvent e) {
			synchronized (stabilityLock) {
				stabilityLock.notifyAll();
			}
		}

		@Override
		public void simulatorStateChanged(SimulatorEvent e) {
			synchronized (stabilityLock) {
				stabilityLock.notifyAll();
			}
		}

		@Override
		public void tickCompleted(SimulatorEvent e) {
			synchronized (stabilityLock) {
				stabilityLock.notifyAll();
			}
		}
	}

	public LogisimSessionContext(String circPath) throws IOException {
		Loader loader = new HeadlessLoader();
		File file = new File(circPath);
		if (!file.exists()) {
			throw new IOException("File not found: " + circPath);
		}

		try {
			this.logisimFile = loader.openLogisimFile(file);
		} catch (LoadFailedException e) {
			throw new IOException(e.getMessage(), e);
		}
		this.project = new Project(logisimFile);

		// Re-enable background simulation to support visual updates (brightness, LEDs)
		// But we will use stabilityListener to wait for completion in API calls.
		Simulator sim = project.getSimulator();
		sim.addSimulatorListener(stabilityListener);
		sim.setIsRunning(true);
		// Keep default state frozen. Continuous ticking is only enabled by
		// runUntilStableThenTick and is always stopped when that macro ends.
		sim.setIsTicking(false);

		this.canvas = new HeadlessCanvas(project);

		refreshComponentCache();
	}

	private void waitForStability() {
		CircuitState state = project.getCircuitState();
		if (state == null)
			return;
		Propagator prop = state.getPropagator();
		if (prop == null)
			return;

		synchronized (stabilityLock) {
			long start = System.currentTimeMillis();
			// Wait up to 2 seconds for propagation to become idle
			while (prop.isPending() && System.currentTimeMillis() - start < 2000) {
				try {
					stabilityLock.wait(50);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					break;
				}
			}
		}
	}

	private void refreshComponentCache() {
		componentCache.clear();
		inputComponentCache.clear();
		outputComponentCache.clear();
		componentIdCache.clear();
		reverseComponentIdCache.clear();
		Circuit circuit = project.getCurrentCircuit();
		if (circuit == null)
			return;

		for (Component comp : circuit.getNonWires()) {
			String label = comp.getAttributeSet().getValue(StdAttr.LABEL);
			if (label != null && !label.trim().isEmpty()) {
				String lbl = label.trim();
				addToCache(componentCache, lbl, comp);

				String factoryName = comp.getFactory().getName();
				if (factoryName.equals("Pin") || factoryName.equals("Button")) {
					Boolean isOutput = (Boolean) comp.getAttributeSet().getValue(Pin.ATTR_TYPE);
					if (isOutput != null && isOutput) {
						addToCache(outputComponentCache, lbl, comp);
					} else {
						addToCache(inputComponentCache, lbl, comp);
					}
				}
			}
		}

		// For exactly two same-labeled IO components, expose left/right aliases.
		buildDirectionalAliases(inputComponentCache);
		buildDirectionalAliases(outputComponentCache);

		// Infer labels for unlabeled IO components by nearby text objects.
		buildNearbyTextAliases(circuit);
		rebuildComponentIdCache(circuit);
	}

	private void addToCache(Map<String, List<Component>> cache, String label, Component comp) {
		List<Component> comps = cache.computeIfAbsent(label, k -> new ArrayList<>());
		if (!comps.contains(comp)) {
			comps.add(comp);
		}
	}

	private String getComponentLabel(Component comp) {
		String label = comp.getAttributeSet().getValue(StdAttr.LABEL);
		if (label == null) {
			return "";
		}
		return label.trim();
	}

	private String sanitizeIdPart(String s) {
		if (s == null || s.isEmpty()) {
			return "_";
		}
		StringBuilder sb = new StringBuilder(s.length());
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (Character.isLetterOrDigit(c) || c == '_' || c == '-') {
				sb.append(c);
			} else {
				sb.append('_');
			}
		}
		return sb.toString();
	}

	private void rebuildComponentIdCache(Circuit circuit) {
		if (circuit == null) {
			return;
		}

		List<Component> components = new ArrayList<>();
		for (Component comp : circuit.getNonWires()) {
			components.add(comp);
		}

		Collections.sort(components,
			Comparator.comparingInt((Component c) -> c.getLocation().getX())
				.thenComparingInt(c -> c.getLocation().getY())
				.thenComparing(c -> c.getFactory().getName())
				.thenComparing(this::getComponentLabel));

		Map<String, Integer> keyCount = new ConcurrentHashMap<>();
		String circuitName = sanitizeIdPart(circuit.getName());

		for (Component comp : components) {
			String factory = sanitizeIdPart(comp.getFactory().getName());
			String label = sanitizeIdPart(getComponentLabel(comp));
			int x = comp.getLocation().getX();
			int y = comp.getLocation().getY();
			String base = circuitName + ":" + factory + ":" + x + ":" + y + ":" + label;
			int index = keyCount.merge(base, 1, Integer::sum);
			String compId = base + ":" + index;
			componentIdCache.put(compId, comp);
			reverseComponentIdCache.put(comp, compId);
		}
	}

	private Component requireComponentById(String compId) {
		if (compId == null || compId.trim().isEmpty()) {
			throw new IllegalArgumentException("comp_id must not be empty.");
		}
		Component comp = componentIdCache.get(compId);
		if (comp == null) {
			throw new IllegalArgumentException("Component not found by comp_id: " + compId
				+ ". Call list_components again after switching circuit.");
		}
		return comp;
	}

	private LinkedHashMap<String, Object> getMemoryInfo(Component comp) {
		LinkedHashMap<String, Object> memory = new LinkedHashMap<>();
		var addrAttrKey = comp.getAttributeSet().getAttribute("addrWidth");
		var dataAttrKey = comp.getAttributeSet().getAttribute("dataWidth");
		BitWidth addrAttr = null;
		BitWidth dataAttr = null;
		if (addrAttrKey != null) {
			@SuppressWarnings({"rawtypes", "unchecked"})
			Object raw =
				comp.getAttributeSet().getValue((com.cburch.logisim.data.Attribute) addrAttrKey);
			if (raw instanceof BitWidth)
				addrAttr = (BitWidth) raw;
		}
		if (dataAttrKey != null) {
			@SuppressWarnings({"rawtypes", "unchecked"})
			Object raw =
				comp.getAttributeSet().getValue((com.cburch.logisim.data.Attribute) dataAttrKey);
			if (raw instanceof BitWidth)
				dataAttr = (BitWidth) raw;
		}
		boolean isMemory = addrAttr != null && dataAttr != null;
		memory.put("isMemory", isMemory);
		if (isMemory) {
			memory.put("addrBits", addrAttr.getWidth());
			memory.put("dataBits", dataAttr.getWidth());
			memory.put("capacity", 1L << addrAttr.getWidth());
		}
		return memory;
	}

	private List<String> collectAliases(Component comp) {
		List<String> aliases = new ArrayList<>();
		for (Map.Entry<String, List<Component>> entry : componentCache.entrySet()) {
			List<Component> comps = entry.getValue();
			if (comps == null || comps.size() != 1) {
				continue;
			}
			if (Objects.equals(comps.get(0), comp)) {
				addIfAbsent(aliases, entry.getKey());
			}
		}
		for (Map.Entry<String, List<Component>> entry : inputComponentCache.entrySet()) {
			List<Component> comps = entry.getValue();
			if (comps == null || comps.size() != 1) {
				continue;
			}
			if (Objects.equals(comps.get(0), comp)) {
				addIfAbsent(aliases, "input" + entry.getKey());
			}
		}
		for (Map.Entry<String, List<Component>> entry : outputComponentCache.entrySet()) {
			List<Component> comps = entry.getValue();
			if (comps == null || comps.size() != 1) {
				continue;
			}
			if (Objects.equals(comps.get(0), comp)) {
				addIfAbsent(aliases, "output" + entry.getKey());
			}
		}
		return aliases;
	}

	private List<String> collectNeighborHints(Component comp, int limit) {
		Circuit circuit = project.getCurrentCircuit();
		if (circuit == null) {
			return new ArrayList<>();
		}
		List<Map.Entry<String, Long>> ranked = new ArrayList<>();
		for (Component candidate : circuit.getNonWires()) {
			if (candidate == comp) {
				continue;
			}
			String lbl = getComponentLabel(candidate);
			if (lbl.isEmpty()) {
				continue;
			}
			long dist = distanceSquaredToBounds(comp, candidate);
			ranked.add(Map.entry(lbl, dist));
		}
		ranked.sort(Comparator.comparingLong(Map.Entry::getValue));

		List<String> hints = new ArrayList<>();
		for (Map.Entry<String, Long> item : ranked) {
			addIfAbsent(hints, item.getKey());
			if (hints.size() >= limit) {
				break;
			}
		}
		return hints;
	}

	private String makeFingerprint(Component comp, LinkedHashMap<String, Object> memoryInfo) {
		StringBuilder sb = new StringBuilder();
		sb.append(comp.getFactory().getName())
			.append('|')
			.append(getComponentLabel(comp))
			.append('|');
		sb.append(comp.getLocation().getX())
			.append('|')
			.append(comp.getLocation().getY())
			.append('|');
		sb.append(memoryInfo.get("isMemory"));
		if (Boolean.TRUE.equals(memoryInfo.get("isMemory"))) {
			sb.append('|')
				.append(memoryInfo.get("addrBits"))
				.append('|')
				.append(memoryInfo.get("dataBits"));
		}
		return Integer.toHexString(sb.toString().hashCode());
	}

	private LinkedHashMap<String, Object> toComponentCard(Component comp) {
		LinkedHashMap<String, Object> memoryInfo = getMemoryInfo(comp);
		LinkedHashMap<String, Object> card = new LinkedHashMap<>();
		String compId = reverseComponentIdCache.get(comp);
		card.put("comp_id", compId);
		card.put("circuit_name",
			project.getCurrentCircuit() == null ? "" : project.getCurrentCircuit().getName());
		card.put("factory_name", comp.getFactory().getName());
		card.put("label", getComponentLabel(comp));
		card.put("x", comp.getLocation().getX());
		card.put("y", comp.getLocation().getY());
		card.put("is_memory", memoryInfo.get("isMemory"));
		if (Boolean.TRUE.equals(memoryInfo.get("isMemory"))) {
			card.put("addr_bits", memoryInfo.get("addrBits"));
			card.put("data_bits", memoryInfo.get("dataBits"));
			card.put("capacity", memoryInfo.get("capacity"));
		}
		card.put("aliases", collectAliases(comp));
		card.put("neighbor_hints", collectNeighborHints(comp, 4));
		String label = getComponentLabel(comp);
		String humanName = (label.isEmpty() ? comp.getFactory().getName() : label) + " @("
			+ comp.getLocation().getX() + "," + comp.getLocation().getY() + ")";
		card.put("human_name", humanName);
		card.put("fingerprint", makeFingerprint(comp, memoryInfo));
		return card;
	}

	private static final class TextCandidate {
		final String text;
		final Component textComponent;

		TextCandidate(String text, Component textComponent) {
			this.text = text;
			this.textComponent = textComponent;
		}
	}

	private static final class TextAliasMatch {
		final String text;
		final Component target;
		final long distanceSq;

		TextAliasMatch(String text, Component target, long distanceSq) {
			this.text = text;
			this.target = target;
			this.distanceSq = distanceSq;
		}
	}

	private String extractTextLabel(Component comp) {
		String text = null;
		try {
			text = comp.getAttributeSet().getValue(Text.ATTR_TEXT);
		} catch (Exception ignored) {
			// Fallback to generic attribute lookup below.
		}

		if (text == null) {
			var textAttr = comp.getAttributeSet().getAttribute("text");
			if (textAttr != null) {
				@SuppressWarnings({"rawtypes", "unchecked"})
				Object raw =
					comp.getAttributeSet().getValue((com.cburch.logisim.data.Attribute) textAttr);
				if (raw != null) {
					text = raw.toString();
				}
			}
		}

		if (text == null) {
			return null;
		}

		String trimmed = text.trim();
		if (trimmed.isEmpty()) {
			return null;
		}

		int newline = trimmed.indexOf('\n');
		if (newline >= 0) {
			trimmed = trimmed.substring(0, newline).trim();
		}
		return trimmed.isEmpty() ? null : trimmed;
	}

	private static long distanceSquaredToBounds(Component source, Component target) {
		int px = source.getLocation().getX();
		int py = source.getLocation().getY();
		Bounds b = target.getBounds();

		int left = b.getX();
		int top = b.getY();
		int right = left + b.getWidth();
		int bottom = top + b.getHeight();

		long dx = 0;
		if (px < left) {
			dx = left - px;
		} else if (px > right) {
			dx = px - right;
		}

		long dy = 0;
		if (py < top) {
			dy = top - py;
		} else if (py > bottom) {
			dy = py - bottom;
		}

		return dx * dx + dy * dy;
	}

	private void addNearbyTextAlias(String text, Component comp) {
		if (text == null || text.isEmpty() || comp == null) {
			return;
		}

		addToCache(componentCache, text, comp);

		String fn = comp.getFactory().getName();
		if (fn.equals("Button")) {
			addToCache(inputComponentCache, text, comp);
			return;
		}

		if (!fn.equals("Pin")) {
			if (fn.equals("Probe")) {
				addToCache(outputComponentCache, text, comp);
			}
			return;
		}

		Boolean isOutput = (Boolean) comp.getAttributeSet().getValue(Pin.ATTR_TYPE);
		if (isOutput != null && isOutput) {
			addToCache(outputComponentCache, text, comp);
		} else {
			addToCache(inputComponentCache, text, comp);
		}
	}

	private static boolean isUnlabeled(Component comp) {
		String label = comp.getAttributeSet().getValue(StdAttr.LABEL);
		return label == null || label.trim().isEmpty();
	}

	private static boolean isInputAliasCandidate(Component comp) {
		String fn = comp.getFactory().getName();
		if (fn.equals("Button")) {
			return true;
		}
		if (!fn.equals("Pin")) {
			return false;
		}
		Boolean isOutput = (Boolean) comp.getAttributeSet().getValue(Pin.ATTR_TYPE);
		return isOutput == null || !isOutput;
	}

	private static boolean isReadableAliasCandidate(Component comp) {
		String fn = comp.getFactory().getName();
		return fn.equals("Pin") || fn.equals("Button") || fn.equals("Probe");
	}

	private TextAliasMatch resolveNearestAlias(TextCandidate text, List<Component> candidates) {
		Component nearest = null;
		long nearestDist = Long.MAX_VALUE;
		long secondDist = Long.MAX_VALUE;

		for (Component comp : candidates) {
			long d = distanceSquaredToBounds(text.textComponent, comp);
			if (d < nearestDist) {
				secondDist = nearestDist;
				nearestDist = d;
				nearest = comp;
			} else if (d < secondDist) {
				secondDist = d;
			}
		}

		if (nearest == null || nearestDist > TEXT_ALIAS_MAX_DISTANCE_SQ) {
			return null;
		}

		if (secondDist != Long.MAX_VALUE) {
			boolean tooCloseByGap = (secondDist - nearestDist) < TEXT_ALIAS_MIN_GAP_SQ;
			boolean tooCloseByRatio = secondDist < nearestDist * 2;
			if (tooCloseByGap || tooCloseByRatio) {
				return null;
			}
		}

		return new TextAliasMatch(text.text, nearest, nearestDist);
	}

	private void applyBestTextAliases(List<TextCandidate> texts, List<Component> candidates) {
		if (texts.isEmpty() || candidates.isEmpty()) {
			return;
		}

		Map<String, TextAliasMatch> bestByText = new ConcurrentHashMap<>();
		for (TextCandidate text : texts) {
			TextAliasMatch match = resolveNearestAlias(text, candidates);
			if (match == null) {
				continue;
			}

			TextAliasMatch prev = bestByText.get(match.text);
			if (prev == null || match.distanceSq < prev.distanceSq) {
				bestByText.put(match.text, match);
			}
		}

		for (TextAliasMatch match : bestByText.values()) {
			addNearbyTextAlias(match.text, match.target);
		}
	}

	private void buildNearbyTextAliases(Circuit circuit) {
		List<Component> unlabeledReadable = new ArrayList<>();
		List<Component> unlabeledInput = new ArrayList<>();
		List<TextCandidate> texts = new ArrayList<>();

		for (Component comp : circuit.getNonWires()) {
			String fn = comp.getFactory().getName();
			if (fn.equals("Text")) {
				String text = extractTextLabel(comp);
				if (text != null) {
					texts.add(new TextCandidate(text, comp));
				}
				continue;
			}

			if (!isUnlabeled(comp)) {
				continue;
			}

			if (isReadableAliasCandidate(comp)) {
				unlabeledReadable.add(comp);
			}

			if (isInputAliasCandidate(comp)) {
				unlabeledInput.add(comp);
			}
		}

		if (texts.isEmpty()) {
			return;
		}

		applyBestTextAliases(texts, unlabeledReadable);
		applyBestTextAliases(texts, unlabeledInput);
	}

	private static int compareByLocation(Component a, Component b) {
		int ax = a.getLocation().getX();
		int bx = b.getLocation().getX();
		if (ax != bx) {
			return Integer.compare(ax, bx);
		}
		int ay = a.getLocation().getY();
		int by = b.getLocation().getY();
		return Integer.compare(ay, by);
	}

	private void addAliasToCache(Map<String, List<Component>> cache, String alias, Component comp) {
		cache.computeIfAbsent(alias, k -> new ArrayList<>()).add(comp);
	}

	private void buildDirectionalAliases(Map<String, List<Component>> cache) {
		for (Map.Entry<String, List<Component>> entry : new ArrayList<>(cache.entrySet())) {
			String label = entry.getKey();
			List<Component> comps = entry.getValue();
			if (comps == null || comps.size() != 2) {
				continue;
			}

			Component first = comps.get(0);
			Component second = comps.get(1);
			Component left = first;
			Component right = second;
			if (compareByLocation(second, first) < 0) {
				left = second;
				right = first;
			}

			addAliasToCache(cache, "left" + label, left);
			addAliasToCache(cache, "right" + label, right);
		}
	}

	private Component selectPreferredComponent(List<Component> candidates) {
		if (candidates == null || candidates.isEmpty())
			return null;
		if (candidates.size() == 1) {
			return candidates.get(0);
		}

		String message =
			"Ambiguous target: " + candidates.size() + " components share the same label.";
		if (candidates.size() == 2) {
			message +=
				" Use left/right disambiguation (for example inputleft<Label>/inputright<Label> or "
				+ "outputleft<Label>/outputright<Label>).";
		} else {
			message += " Assign unique labels to these components.";
		}
		throw new IllegalArgumentException(message);
	}

	private Component findInCache(Map<String, List<Component>> cache, String target) {
		List<Component> candidates = cache.get(target);
		return selectPreferredComponent(candidates);
	}

	private static void addIfAbsent(List<String> list, String value) {
		if (!list.contains(value)) {
			list.add(value);
		}
	}

	private Component findByFactoryAndDirection(String factoryName, boolean expectOutput) {
		Circuit circuit = project.getCurrentCircuit();
		if (circuit == null)
			return null;

		List<Component> matches = new ArrayList<>();
		for (Component comp : circuit.getNonWires()) {
			if (!comp.getFactory().getName().equalsIgnoreCase(factoryName)) {
				continue;
			}

			String fn = comp.getFactory().getName();
			if (!(fn.equals("Pin") || fn.equals("Button"))) {
				continue;
			}

			if (fn.equals("Button")) {
				if (!expectOutput) {
					matches.add(comp);
				}
				continue;
			}

			Boolean isOutput = (Boolean) comp.getAttributeSet().getValue(Pin.ATTR_TYPE);
			boolean compIsOutput = isOutput != null && isOutput;
			if (compIsOutput == expectOutput) {
				matches.add(comp);
			}
		}

		if (matches.size() == 1)
			return matches.get(0);
		if (matches.isEmpty())
			return null;
		throw new IllegalArgumentException("Ambiguous target '"
			+ (expectOutput ? "output" : "input") + factoryName + "': " + matches.size()
			+ " components match this prefixed factory name. "
			+ "Assign a unique label to each component to distinguish them.");
	}

	private Component findByPrefixedName(String target) {
		if (target == null)
			return null;
		if (target.length() > 5 && target.regionMatches(true, 0, "input", 0, 5)) {
			String key = target.substring(5).trim();
			if (!key.isEmpty()) {
				Component fromInputCache = findInCache(inputComponentCache, key);
				if (fromInputCache != null) {
					return fromInputCache;
				}
				return findByFactoryAndDirection(key, false);
			}
		}
		if (target.length() > 6 && target.regionMatches(true, 0, "output", 0, 6)) {
			String key = target.substring(6).trim();
			if (!key.isEmpty()) {
				Component fromOutputCache = findInCache(outputComponentCache, key);
				if (fromOutputCache != null) {
					return fromOutputCache;
				}
				return findByFactoryAndDirection(key, true);
			}
		}
		return null;
	}

	/**
	 * Fallback: if no component is found by label, searches the current circuit
	 * for components whose factory name equals {@code factoryName}.
	 * Returns the component if exactly one matches; throws if multiple match
	 * (ambiguous); returns {@code null} if none match.
	 */
	private Component findByUniqueName(String factoryName) {
		Component prefixed = findByPrefixedName(factoryName);
		if (prefixed != null) {
			return prefixed;
		}

		Circuit circuit = project.getCurrentCircuit();
		if (circuit == null)
			return null;
		List<Component> matches = new ArrayList<>();
		for (Component comp : circuit.getNonWires()) {
			if (comp.getFactory().getName().equalsIgnoreCase(factoryName)) {
				matches.add(comp);
			}
		}
		if (matches.size() == 1)
			return matches.get(0);
		if (matches.isEmpty())
			return null;
		throw new IllegalArgumentException("Ambiguous target '" + factoryName
			+ "': " + matches.size() + " components share that factory name. "
			+ "Assign a unique label to each component to distinguish them.");
	}

	public List<String> getCircuits() {
		List<String> names = new ArrayList<>();
		for (Circuit c : logisimFile.getCircuits()) {
			names.add(c.getName());
		}
		return names;
	}

	public void switch_circuit(String name) {
		Circuit circuit = logisimFile.getCircuit(name);
		if (circuit == null) {
			throw new IllegalArgumentException("The circuit " + name + " does not exist.");
		}
		project.setCurrentCircuit(circuit);
		refreshComponentCache();
	}

	public List<LinkedHashMap<String, Object>> listComponents(
		String factoryName, String label, Boolean isMemory, Integer addrBits, Integer dataBits) {
		Circuit circuit = project.getCurrentCircuit();
		if (circuit == null) {
			throw new IllegalArgumentException("No active circuit.");
		}

		List<LinkedHashMap<String, Object>> result = new ArrayList<>();
		for (Component comp : circuit.getNonWires()) {
			LinkedHashMap<String, Object> card = toComponentCard(comp);
			if (factoryName != null && !factoryName.trim().isEmpty()) {
				String fn = (String) card.get("factory_name");
				if (!fn.equalsIgnoreCase(factoryName.trim())) {
					continue;
				}
			}
			if (label != null && !label.trim().isEmpty()) {
				String lbl = (String) card.get("label");
				if (lbl == null || !lbl.equals(label.trim())) {
					continue;
				}
			}
			if (isMemory != null && !Objects.equals(card.get("is_memory"), isMemory)) {
				continue;
			}
			if (addrBits != null
				&& (!card.containsKey("addr_bits")
					|| !Objects.equals(card.get("addr_bits"), addrBits))) {
				continue;
			}
			if (dataBits != null
				&& (!card.containsKey("data_bits")
					|| !Objects.equals(card.get("data_bits"), dataBits))) {
				continue;
			}
			result.add(card);
		}

		result.sort(
			Comparator.<LinkedHashMap<String, Object>, Integer>comparing(c -> (Integer) c.get("x"))
				.thenComparing(c -> (Integer) c.get("y"))
				.thenComparing(c -> (String) c.get("factory_name"))
				.thenComparing(c -> (String) c.get("label")));
		return result;
	}

	private List<Component> resolveCandidatesByTarget(String target) {
		if (target == null || target.trim().isEmpty()) {
			return new ArrayList<>();
		}
		String trimmed = target.trim();
		List<Component> fromLabel = componentCache.get(trimmed);
		if (fromLabel != null && !fromLabel.isEmpty()) {
			return new ArrayList<>(fromLabel);
		}

		Component prefixed = findByPrefixedName(trimmed);
		if (prefixed != null) {
			List<Component> one = new ArrayList<>();
			one.add(prefixed);
			return one;
		}

		Circuit circuit = project.getCurrentCircuit();
		List<Component> matches = new ArrayList<>();
		if (circuit == null) {
			return matches;
		}
		for (Component comp : circuit.getNonWires()) {
			if (comp.getFactory().getName().equalsIgnoreCase(trimmed)) {
				matches.add(comp);
			}
		}
		return matches;
	}

	private List<Component> applyResolveFilters(List<Component> candidates, String factoryName,
		String label, Boolean isMemory, Integer addrBits, Integer dataBits) {
		List<Component> filtered = new ArrayList<>();
		for (Component comp : candidates) {
			if (factoryName != null && !factoryName.trim().isEmpty()
				&& !comp.getFactory().getName().equalsIgnoreCase(factoryName.trim())) {
				continue;
			}
			if (label != null && !label.trim().isEmpty()
				&& !getComponentLabel(comp).equals(label.trim())) {
				continue;
			}

			LinkedHashMap<String, Object> mem = getMemoryInfo(comp);
			if (isMemory != null && !Objects.equals(mem.get("isMemory"), isMemory)) {
				continue;
			}
			if (addrBits != null
				&& (!Boolean.TRUE.equals(mem.get("isMemory"))
					|| !Objects.equals(mem.get("addrBits"), addrBits))) {
				continue;
			}
			if (dataBits != null
				&& (!Boolean.TRUE.equals(mem.get("isMemory"))
					|| !Objects.equals(mem.get("dataBits"), dataBits))) {
				continue;
			}
			filtered.add(comp);
		}
		return filtered;
	}

	public LinkedHashMap<String, Object> resolveComponent(String target, String factoryName,
		String label, Boolean isMemory, Integer addrBits, Integer dataBits, Integer index,
		String sort) {
		Circuit circuit = project.getCurrentCircuit();
		if (circuit == null) {
			throw new IllegalArgumentException("No active circuit.");
		}

		List<Component> seed;
		if (target != null && !target.trim().isEmpty()) {
			seed = resolveCandidatesByTarget(target);
		} else {
			seed = new ArrayList<>();
			for (Component comp : circuit.getNonWires()) {
				seed.add(comp);
			}
		}

		List<Component> filtered =
			applyResolveFilters(seed, factoryName, label, isMemory, addrBits, dataBits);

		Comparator<Component> comparator =
			Comparator.comparingInt((Component c) -> c.getLocation().getX())
				.thenComparingInt(c -> c.getLocation().getY());
		if (sort != null && sort.equalsIgnoreCase("top_to_bottom")) {
			comparator = Comparator.comparingInt((Component c) -> c.getLocation().getY())
							 .thenComparingInt(c -> c.getLocation().getX());
		}
		filtered.sort(comparator);

		LinkedHashMap<String, Object> result = new LinkedHashMap<>();
		if (filtered.isEmpty()) {
			throw new IllegalArgumentException("No component matches resolve_component filters.");
		}

		if (index != null) {
			if (index < 0 || index >= filtered.size()) {
				throw new IllegalArgumentException(
					"index out of range for resolve_component candidates: " + index);
			}
			Component chosen = filtered.get(index);
			result.put("resolved", true);
			result.put("comp_id", reverseComponentIdCache.get(chosen));
			result.put("component", toComponentCard(chosen));
			return result;
		}

		if (filtered.size() == 1) {
			Component chosen = filtered.get(0);
			result.put("resolved", true);
			result.put("comp_id", reverseComponentIdCache.get(chosen));
			result.put("component", toComponentCard(chosen));
			return result;
		}

		List<LinkedHashMap<String, Object>> candidates = new ArrayList<>();
		for (Component comp : filtered) {
			candidates.add(toComponentCard(comp));
		}
		result.put("resolved", false);
		result.put("reason", "multiple candidates matched");
		result.put("candidate_count", candidates.size());
		result.put("candidates", candidates);
		result.put("hint", "Provide index or more filters such as addr_bits/data_bits/label.");
		return result;
	}

	public LinkedHashMap<String, Object> getComponentInfoById(String compId) {
		Component comp = requireComponentById(compId);
		LinkedHashMap<String, Object> info = new LinkedHashMap<>();
		info.put("comp_id", compId);
		info.put("type", comp.getFactory().getName());
		info.put("label", getComponentLabel(comp));
		LinkedHashMap<String, Object> memoryInfo = getMemoryInfo(comp);
		info.put("isMemory", memoryInfo.get("isMemory"));
		if (Boolean.TRUE.equals(memoryInfo.get("isMemory"))) {
			info.put("addrBits", memoryInfo.get("addrBits"));
			info.put("dataBits", memoryInfo.get("dataBits"));
			info.put("capacity", memoryInfo.get("capacity"));
		}
		info.put("x", comp.getLocation().getX());
		info.put("y", comp.getLocation().getY());
		return info;
	}

	public LinkedHashMap<String, Object> describeComponent(String compId) {
		Component comp = requireComponentById(compId);
		LinkedHashMap<String, Object> card = toComponentCard(comp);
		LinkedHashMap<String, Object> description = new LinkedHashMap<>();
		description.put("comp_id", compId);
		description.put("human_name", card.get("human_name"));
		description.put("summary",
			"type=" + card.get("factory_name") + ", label='" + card.get("label") + "', location=("
				+ card.get("x") + "," + card.get("y") + ")");
		description.put("component", card);
		description.put("recommended_usage",
			Boolean.TRUE.equals(card.get("is_memory"))
				? "Use load_memory_by_id for deterministic memory loading."
				: "Use by-id value APIs to avoid label ambiguity.");
		return description;
	}

	public Map<String, List<String>> getIO() {
		List<String> inputs = new ArrayList<>();
		for (Map.Entry<String, List<Component>> entry : inputComponentCache.entrySet()) {
			if (entry.getValue() != null && entry.getValue().size() == 1) {
				addIfAbsent(inputs, "input" + entry.getKey());
			}
		}

		List<String> outputs = new ArrayList<>();
		for (Map.Entry<String, List<Component>> entry : outputComponentCache.entrySet()) {
			if (entry.getValue() != null && entry.getValue().size() == 1) {
				addIfAbsent(outputs, "output" + entry.getKey());
			}
		}

		List<String> allLabeled = new ArrayList<>();
		for (Map.Entry<String, List<Component>> entry : componentCache.entrySet()) {
			String key = entry.getKey();
			List<Component> comps = entry.getValue();
			if (comps == null || comps.size() != 1) {
				continue;
			}
			if (inputComponentCache.containsKey(key) && inputComponentCache.get(key) != null
				&& inputComponentCache.get(key).size() == 1) {
				addIfAbsent(allLabeled, "input" + key);
			} else if (outputComponentCache.containsKey(key)
				&& outputComponentCache.get(key) != null
				&& outputComponentCache.get(key).size() == 1) {
				addIfAbsent(allLabeled, "output" + key);
			} else {
				addIfAbsent(allLabeled, key);
			}
		}

		// Add unambiguous aliases for unlabeled components.
		// IO aliases use directional prefixes: inputXxx / outputXxx.
		Circuit circuit = project.getCurrentCircuit();
		if (circuit != null) {
			Map<String, Integer> inputFactoryTotalCount = new ConcurrentHashMap<>();
			Map<String, Integer> outputFactoryTotalCount = new ConcurrentHashMap<>();
			Map<String, Integer> inputFactoryCount = new ConcurrentHashMap<>();
			Map<String, Integer> outputFactoryCount = new ConcurrentHashMap<>();
			Map<String, Integer> unlabeledFactoryCount = new ConcurrentHashMap<>();

			for (Component comp : circuit.getNonWires()) {
				String factoryName = comp.getFactory().getName();

				if (factoryName.equals("Pin")) {
					Boolean isOutput = (Boolean) comp.getAttributeSet().getValue(Pin.ATTR_TYPE);
					if (isOutput != null && isOutput) {
						outputFactoryTotalCount.merge(factoryName, 1, Integer::sum);
					} else {
						inputFactoryTotalCount.merge(factoryName, 1, Integer::sum);
					}
				} else if (factoryName.equals("Button")) {
					inputFactoryTotalCount.merge(factoryName, 1, Integer::sum);
				}

				String label = (String) comp.getAttributeSet().getValue(StdAttr.LABEL);
				if (label == null || label.trim().isEmpty()) {
					unlabeledFactoryCount.merge(factoryName, 1, Integer::sum);

					if (factoryName.equals("Pin")) {
						Boolean isOutput = (Boolean) comp.getAttributeSet().getValue(Pin.ATTR_TYPE);
						if (isOutput != null && isOutput) {
							outputFactoryCount.merge(factoryName, 1, Integer::sum);
						} else {
							inputFactoryCount.merge(factoryName, 1, Integer::sum);
						}
					} else if (factoryName.equals("Button")) {
						inputFactoryCount.merge(factoryName, 1, Integer::sum);
					}
				}
			}

			for (String factoryName : inputFactoryTotalCount.keySet()) {
				if (inputFactoryTotalCount.get(factoryName) == 1) {
					String alias = "input" + factoryName;
					addIfAbsent(inputs, alias);
					addIfAbsent(allLabeled, alias);
				}
			}

			for (String factoryName : outputFactoryTotalCount.keySet()) {
				if (outputFactoryTotalCount.get(factoryName) == 1) {
					String alias = "output" + factoryName;
					addIfAbsent(outputs, alias);
					addIfAbsent(allLabeled, alias);
				}
			}

			for (String factoryName : inputFactoryCount.keySet()) {
				if (inputFactoryCount.get(factoryName) == 1) {
					String alias = "input" + factoryName;
					addIfAbsent(inputs, alias);
					addIfAbsent(allLabeled, alias);
				}
			}

			for (String factoryName : outputFactoryCount.keySet()) {
				if (outputFactoryCount.get(factoryName) == 1) {
					String alias = "output" + factoryName;
					addIfAbsent(outputs, alias);
					addIfAbsent(allLabeled, alias);
				}
			}

			for (String factoryName : unlabeledFactoryCount.keySet()) {
				if (unlabeledFactoryCount.get(factoryName) == 1) {
					addIfAbsent(allLabeled, factoryName);
				}
			}
		}

		Map<String, List<String>> res = new ConcurrentHashMap<>();
		res.put("inputs", inputs);
		res.put("outputs", outputs);
		res.put("all_labeled", allLabeled);
		return res;
	}

	public void setValue(String target, String valueStr) {
		if (target == null || valueStr == null) {
			throw new IllegalArgumentException("Target and value must not be null.");
		}
		Component comp = findInCache(inputComponentCache, target);
		if (comp == null) {
			if (outputComponentCache.containsKey(target)) {
				throw new IllegalArgumentException(
					"Pin '" + target + "' is an output pin and cannot be changed.");
			}
			// Fallback: try to locate a unique component by factory name
			Component fallback = findByUniqueName(target);
			if (fallback != null) {
				String fn = fallback.getFactory().getName();
				if (!fn.equals("Pin") && !fn.equals("Button")) {
					throw new IllegalArgumentException("Component '" + target + "' (type: " + fn
						+ ") is not a settable input pin.");
				}
				Boolean isOut = (Boolean) fallback.getAttributeSet().getValue(Pin.ATTR_TYPE);
				if (isOut != null && isOut) {
					throw new IllegalArgumentException(
						"Pin '" + target + "' is an output pin and cannot be changed.");
				}
				comp = fallback;
			} else {
				throw new IllegalArgumentException("Input component not found: " + target);
			}
		}

		// Validation: Only input pins can be set
		if ((!comp.getFactory().getName().equals("Pin"))
			&& (!comp.getFactory().getName().equals("Button"))) {
			throw new IllegalArgumentException(
				"Component '" + target + "(" + comp.getFactory().getName() + ")' cannot change.");
		}
		Boolean isOutput = (Boolean) comp.getAttributeSet().getValue(Pin.ATTR_TYPE);
		if (isOutput != null && isOutput) {
			throw new IllegalArgumentException(
				"Pin '" + target + "' is an output pin and cannot be changed.");
		}

		CircuitState state = project.getCircuitState();
		BitWidth width = comp.getAttributeSet().getValue(StdAttr.WIDTH);
		if (width == null)
			width = BitWidth.ONE;
		Value val = parseValue(valueStr, width);

		// Force value into circuit state (this drives signal colors/rendering)
		state.setValue(comp.getLocation(), val, comp, 1);

		// Sync Pin's internal state for visual binary labels
		Instance instance = Instance.getInstanceFor(comp);
		if (instance != null && instance.getFactory() instanceof Pin) {
			InstanceState instState = state.getInstanceState(comp);
			((Pin) instance.getFactory()).setValue(instState, val);
		}

		// Explicitly request propagation for the background thread
		project.getSimulator().requestPropagate();

		// Setting a value will trigger propagation in the background
		// We wait for it to stabilize
		waitForStability();
	}

	public String getValue(String target) {
		if (target == null) {
			throw new IllegalArgumentException("Target must not be null.");
		}
		Component comp = findInCache(componentCache, target);
		if (comp == null) {
			comp = findByUniqueName(target);
			if (comp == null)
				throw new IllegalArgumentException("Component not found: " + target);
		}

		waitForStability(); // Ensure stable state before reading
		CircuitState state = project.getCircuitState();
		Value val = state.getValue(comp.getLocation());
		String hex = val.toHexString();
		if (hex != null && !hex.isEmpty()) {
			boolean allUnknown = true;
			for (int i = 0; i < hex.length(); i++) {
				char c = Character.toLowerCase(hex.charAt(i));
				if (c != 'x' && c != 'z') {
					allUnknown = false;
					break;
				}
			}
			if (allUnknown) {
				return hex;
			}
		}
		return "0x" + hex;
	}

	public void checkValue(String target, String expected) {
		if (target == null) {
			throw new IllegalArgumentException("Target must not be null.");
		}
		Component comp = findInCache(componentCache, target);
		if (comp == null) {
			comp = findByUniqueName(target);
			if (comp == null)
				throw new IllegalArgumentException("Component not found: " + target);
		}

		BitWidth width = comp.getAttributeSet().getValue(StdAttr.WIDTH);
		if (width == null)
			width = BitWidth.ONE;

		String currentValue = getValue(target);
		if (!matches(currentValue, expected, width)) {
			throw new IllegalArgumentException("Value mismatch for '" + target + "': expected "
				+ expected + ", got " + currentValue);
		}
	}

	public int tickUntil(String target, String expected, String clock, int maxTicks) {
		if (target == null) {
			throw new IllegalArgumentException("Target must not be null.");
		}
		Component targetComp = findInCache(componentCache, target);
		if (targetComp == null) {
			targetComp = findByUniqueName(target);
			if (targetComp == null)
				throw new IllegalArgumentException("Component not found: " + target);
		}

		// Resolve clock component and determine tick strategy
		boolean useSimTick = (clock == null || clock.isEmpty());
		if (!useSimTick) {
			Component clockComp = findInCache(inputComponentCache, clock);
			if (clockComp == null) {
				clockComp = findInCache(componentCache, clock);
			}
			if (clockComp == null) {
				clockComp = findByUniqueName(clock);
			}
			if (clockComp == null) {
				throw new IllegalArgumentException("Clock component not found: " + clock);
			}
			String fn = clockComp.getFactory().getName();
			if (fn.equals("Clock")) {
				// Logisim built-in Clock component: use simulator tick
				useSimTick = true;
			} else if (fn.equals("Pin") || fn.equals("Button")) {
				Boolean isOut = (Boolean) clockComp.getAttributeSet().getValue(Pin.ATTR_TYPE);
				if (isOut != null && isOut) {
					throw new IllegalArgumentException(
						"Clock component must be an input, but got output: " + clock);
				}
			} else {
				throw new IllegalArgumentException(
					"Clock component must be input Pin/Button or Clock: " + clock);
			}
		}

		BitWidth width = targetComp.getAttributeSet().getValue(StdAttr.WIDTH);
		if (width == null)
			width = BitWidth.ONE;

		// Preliminary check
		if (matches(getValue(target), expected, width))
			return 0;

		for (int i = 0; i < maxTicks; i++) {
			if (!useSimTick) {
				// Pulse the clock manually (1 then 0)
				setValue(clock, "1");
				setValue(clock, "0");
			} else {
				// Advance internal simulation clock
				project.getSimulator().tick();
				waitForStability();
			}

			// getValue ensures stability and state consistency
			String current = getValue(target);
			if (matches(current, expected, width)) {
				// Guard against transient matches caused by asynchronous propagation.
				// Re-read once without extra tick and only accept a stable hit.
				String confirm = getValue(target);
				if (matches(confirm, expected, width)) {
					return i + 1; // Return 1-based step count
				}
			}
		}
		return -1;
	}

	private boolean matches(String currentHex, String expectedStr, BitWidth width) {
		if (currentHex == null || expectedStr == null)
			return false;
		if (currentHex.equalsIgnoreCase(expectedStr))
			return true;

		try {
			Value v1 = parseValue(currentHex, width);
			Value v2 = parseValue(expectedStr, width);
			return v1.equals(v2);
		} catch (Exception e) {
			return false;
		}
	}

	/**
	 * Returns metadata about a labeled component: its factory type, and for
	 * memory components (ROM/RAM), the address-bit count, data-bit width, and
	 * total capacity.
	 */
	public java.util.LinkedHashMap<String, Object> getComponentInfo(String label) {
		if (label == null)
			throw new IllegalArgumentException("Label must not be null.");

		List<Component> candidates = componentCache.get(label);
		Component comp;
		if (candidates == null || candidates.isEmpty()) {
			comp = findByUniqueName(label);
			if (comp == null)
				throw new IllegalArgumentException("Component not found: " + label);
		} else {
			comp = selectPreferredComponent(candidates);
		}
		String factoryName = comp.getFactory().getName();

		java.util.LinkedHashMap<String, Object> info = new java.util.LinkedHashMap<>();
		info.put("type", factoryName);
		info.put("label", label);

		var addrAttrKey = comp.getAttributeSet().getAttribute("addrWidth");
		var dataAttrKey = comp.getAttributeSet().getAttribute("dataWidth");
		BitWidth addrAttr = null;
		BitWidth dataAttr = null;
		if (addrAttrKey != null) {
			@SuppressWarnings({"rawtypes", "unchecked"})
			Object raw =
				comp.getAttributeSet().getValue((com.cburch.logisim.data.Attribute) addrAttrKey);
			if (raw instanceof BitWidth)
				addrAttr = (BitWidth) raw;
		}
		if (dataAttrKey != null) {
			@SuppressWarnings({"rawtypes", "unchecked"})
			Object raw =
				comp.getAttributeSet().getValue((com.cburch.logisim.data.Attribute) dataAttrKey);
			if (raw instanceof BitWidth)
				dataAttr = (BitWidth) raw;
		}
		if (addrAttr != null && dataAttr != null) {
			info.put("isMemory", true);
			info.put("addrBits", addrAttr.getWidth());
			info.put("dataBits", dataAttr.getWidth());
			info.put("capacity", 1L << addrAttr.getWidth());
		} else {
			info.put("isMemory", false);
		}
		return info;
	}

	/**
	 * Loads memory contents into a labeled ROM component.
	 * Throws if the component is not ROM. Each key in {@code entries}
	 * is a hex/decimal address string; value is the integer data to write.
	 */
	public void loadMemory(String label, java.util.Map<String, Integer> entries) {
		if (label == null)
			throw new IllegalArgumentException("Label must not be null.");

		List<Component> candidates = componentCache.get(label);
		Component comp;
		if (candidates == null || candidates.isEmpty()) {
			comp = findByUniqueName(label);
			if (comp == null)
				throw new IllegalArgumentException("Component not found: " + label);
		} else {
			comp = selectPreferredComponent(candidates);
		}
		String factoryName = comp.getFactory().getName();
		if (!factoryName.equals("ROM") && !factoryName.equals("RAM"))
			throw new IllegalArgumentException("Component '" + label + "' is of type '"
				+ factoryName
				+ "', not ROM or RAM. This load_memory API supports ROM and RAM only.");

		HexModel contents = resolveMemoryHexModel(comp, factoryName, label);

		long maxAddr = contents.getLastOffset();
		int dataMask = (1 << contents.getValueWidth()) - 1;

		for (java.util.Map.Entry<String, Integer> entry : entries.entrySet()) {
			String addrStr = entry.getKey().trim();
			long addr;
			try {
				addr = addrStr.startsWith("0x") || addrStr.startsWith("0X")
					? Long.parseLong(addrStr.substring(2), 16)
					: Long.parseLong(addrStr);
			} catch (NumberFormatException e) {
				throw new IllegalArgumentException("Invalid address format: " + addrStr);
			}
			if (addr < 0 || addr > maxAddr)
				throw new IllegalArgumentException("Address " + addrStr + " out of range [0, "
					+ maxAddr + "] for component '" + label + "'.");

			int data = entry.getValue() & dataMask;
			contents.fill(addr, 1, data);
		}
		if (!factoryName.equals("RAM")) {
			project.getSimulator().requestPropagate();
			waitForStability();
		}
	}

	public void loadMemoryById(String compId, java.util.Map<String, Integer> entries) {
		Component comp = requireComponentById(compId);
		String factoryName = comp.getFactory().getName();
		if (!factoryName.equals("ROM") && !factoryName.equals("RAM")) {
			throw new IllegalArgumentException("Component '" + compId + "' is of type '"
				+ factoryName
				+ "', not ROM or RAM. This load_memory_by_id API supports ROM and RAM only.");
		}

		HexModel contents = resolveMemoryHexModel(comp, factoryName, compId);
		long maxAddr = contents.getLastOffset();
		int dataMask = (1 << contents.getValueWidth()) - 1;

		for (java.util.Map.Entry<String, Integer> entry : entries.entrySet()) {
			String addrStr = entry.getKey().trim();
			long addr;
			try {
				addr = addrStr.startsWith("0x") || addrStr.startsWith("0X")
					? Long.parseLong(addrStr.substring(2), 16)
					: Long.parseLong(addrStr);
			} catch (NumberFormatException e) {
				throw new IllegalArgumentException("Invalid address format: " + addrStr);
			}
			if (addr < 0 || addr > maxAddr) {
				throw new IllegalArgumentException("Address " + addrStr + " out of range [0, "
					+ maxAddr + "] for component '" + compId + "'.");
			}

			int data = entry.getValue() & dataMask;
			contents.fill(addr, 1, data);
		}
		if (!factoryName.equals("RAM")) {
			project.getSimulator().requestPropagate();
			waitForStability();
		}
	}

	/**
	 * Loads ROM/RAM contents from a txt file in Logisim hex format (v2.0 raw).
	 */
	public void loadMemoryFromTxt(String label, String txtPath) {
		if (txtPath == null || txtPath.trim().isEmpty()) {
			throw new IllegalArgumentException("txt_path must not be empty.");
		}

		if (label == null)
			throw new IllegalArgumentException("Label must not be null.");

		List<Component> candidates = componentCache.get(label);
		Component comp;
		if (candidates == null || candidates.isEmpty()) {
			comp = findByUniqueName(label);
			if (comp == null)
				throw new IllegalArgumentException("Component not found: " + label);
		} else {
			comp = selectPreferredComponent(candidates);
		}
		String factoryName = comp.getFactory().getName();
		if (!factoryName.equals("ROM") && !factoryName.equals("RAM")) {
			throw new IllegalArgumentException("Component '" + label + "' is of type '"
				+ factoryName
				+ "', not ROM or RAM. This load_memory API supports ROM and RAM only.");
		}

		HexModel contents = resolveMemoryHexModel(comp, factoryName, label);

		File file = new File(txtPath);
		if (!file.exists() || !file.isFile()) {
			throw new IllegalArgumentException(
				"txt_path does not exist or is not a file: " + txtPath);
		}

		try {
			HexFile.open(contents, file);
		} catch (IOException e) {
			throw new IllegalArgumentException(
				"Failed to load txt memory file. Expected Logisim hex format 'v2.0 raw'. "
				+ "Details: " + e.getMessage());
		}

		if (!factoryName.equals("RAM")) {
			project.getSimulator().requestPropagate();
			waitForStability();
		}
	}

	public void loadMemoryFromTxtById(String compId, String txtPath) {
		if (txtPath == null || txtPath.trim().isEmpty()) {
			throw new IllegalArgumentException("txt_path must not be empty.");
		}

		Component comp = requireComponentById(compId);
		String factoryName = comp.getFactory().getName();
		if (!factoryName.equals("ROM") && !factoryName.equals("RAM")) {
			throw new IllegalArgumentException("Component '" + compId + "' is of type '"
				+ factoryName
				+ "', not ROM or RAM. This load_memory_by_id API supports ROM and RAM only.");
		}

		HexModel contents = resolveMemoryHexModel(comp, factoryName, compId);

		File file = new File(txtPath);
		if (!file.exists() || !file.isFile()) {
			throw new IllegalArgumentException(
				"txt_path does not exist or is not a file: " + txtPath);
		}

		try {
			HexFile.open(contents, file);
		} catch (IOException e) {
			throw new IllegalArgumentException(
				"Failed to load txt memory file. Expected Logisim hex format 'v2.0 raw'. "
				+ "Details: " + e.getMessage());
		}

		if (!factoryName.equals("RAM")) {
			project.getSimulator().requestPropagate();
			waitForStability();
		}
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private HexModel resolveMemoryHexModel(Component comp, String factoryName, String label) {
		// ROM path: contents is usually an attribute named "contents".
		var contentsAttr = comp.getAttributeSet().getAttribute("contents");
		if (contentsAttr != null) {
			Object rawContents =
				comp.getAttributeSet().getValue((com.cburch.logisim.data.Attribute) contentsAttr);
			if (rawContents instanceof HexModel) {
				return (HexModel) rawContents;
			}
		}

		// Fallback by unique factory name (keeps previous robust behavior).
		try {
			Component fallback = findByUniqueName(factoryName);
			if (fallback != null) {
				var fallbackAttr = fallback.getAttributeSet().getAttribute("contents");
				if (fallbackAttr != null) {
					Object rawFallback = fallback.getAttributeSet().getValue(
						(com.cburch.logisim.data.Attribute) fallbackAttr);
					if (rawFallback instanceof HexModel) {
						return (HexModel) rawFallback;
					}
				}
			}
		} catch (IllegalArgumentException e) {
			// If ambiguous, continue to RAM state fallback and then throw unified error.
		}

		// RAM path: contents lives in runtime InstanceState data (MemState/RamState).
		try {
			CircuitState circuitState = project.getCircuitState();
			if (circuitState != null) {
				InstanceState instState = circuitState.getInstanceState(comp);
				if (instState != null) {
					Object data = instState.getData();
					if (data != null) {
						Method getContents = data.getClass().getMethod("getContents");
						getContents.setAccessible(true);
						Object runtimeContents = getContents.invoke(data);
						if (runtimeContents instanceof HexModel) {
							return (HexModel) runtimeContents;
						}
					}
				}
			}
		} catch (ReflectiveOperationException e) {
			// Ignore and throw unified error below.
		}

		throw new IllegalArgumentException(
			"Cannot access memory contents of component '" + label + "'.");
	}

	/**
	 * Macro command that mirrors menu flow:
	 * 1) reset simulation (Ctrl+R)
	 * 2) start continuous clock at 4.1kHz (Ctrl+K)
	 * 3) wait until target value is stable
	 * 4) run additional k ticks
	 *
	 * If timeout is reached at any stage, throws an IllegalArgumentException.
	 */
	public java.util.LinkedHashMap<String, Object> runUntilStableThenTick(String target,
		String expected, int k, double timeoutSeconds, int stableSamples, int pollMs) {
		if (target == null || target.trim().isEmpty()) {
			throw new IllegalArgumentException("target must not be empty.");
		}
		if (timeoutSeconds <= 0) {
			throw new IllegalArgumentException("timeout_second must be > 0.");
		}
		if (k < 0) {
			throw new IllegalArgumentException("k must be >= 0.");
		}
		if (stableSamples <= 0) {
			throw new IllegalArgumentException("stable_samples must be > 0.");
		}
		if (pollMs <= 0) {
			throw new IllegalArgumentException("poll_ms must be > 0.");
		}

		Component targetComp = findInCache(componentCache, target);
		if (targetComp == null) {
			targetComp = findByUniqueName(target);
			if (targetComp == null)
				throw new IllegalArgumentException("Component not found: " + target);
		}
		BitWidth width = targetComp.getAttributeSet().getValue(StdAttr.WIDTH);
		if (width == null)
			width = BitWidth.ONE;

		final long startMs = System.currentTimeMillis();
		final long timeoutMs = (long) Math.round(timeoutSeconds * 1000.0);
		final long deadlineMs = startMs + timeoutMs;

		Simulator sim = project.getSimulator();

		// Ctrl+R: reset simulation state
		sim.requestReset();
		waitForStability();

		// Ctrl+K + highest tick frequency
		sim.setTickFrequency(4100.0);
		sim.setIsRunning(true);
		sim.setIsTicking(true);

		String last = null;
		int stableCount = 0;
		String stableValue = null;
		int pollCount = 0;

		try {
			try {
				while (System.currentTimeMillis() <= deadlineMs) {
					String cur = getValue(target);
					pollCount++;

					boolean expectedOk = true;
					if (expected != null && !expected.trim().isEmpty()) {
						expectedOk = matches(cur, expected, width);
					}

					if (expectedOk) {
						if (cur.equals(last)) {
							stableCount++;
						} else {
							stableCount = 1;
							last = cur;
						}

						if (stableCount >= stableSamples) {
							stableValue = cur;
							break;
						}
					} else {
						stableCount = 0;
						last = cur;
					}

					Thread.sleep(pollMs);
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IllegalArgumentException("Interrupted while waiting for stable value.");
			}

			if (stableValue == null) {
				throw new IllegalArgumentException("Timeout after " + timeoutSeconds
					+ "s while waiting for stable target '" + target + "'.");
			}

			// Stop continuous ticking, then execute precise extra ticks.
			sim.setIsTicking(false);
			for (int i = 0; i < k; i++) {
				if (System.currentTimeMillis() > deadlineMs) {
					throw new IllegalArgumentException(
						"Timeout after " + timeoutSeconds + "s while executing extra k ticks.");
				}
				sim.tick();
				waitForStability();
			}

			String finalValue = getValue(target);
			java.util.LinkedHashMap<String, Object> result = new java.util.LinkedHashMap<>();
			result.put("target", target);
			result.put("stable_value", stableValue);
			result.put("final_value", finalValue);
			result.put("stable_samples", stableSamples);
			result.put("poll_count", pollCount);
			result.put("k_executed", k);
			result.put("tick_frequency_hz", 4100.0);
			result.put("elapsed_ms", System.currentTimeMillis() - startMs);
			return result;
		} finally {
			// Always freeze simulation after macro completion (success or error).
			sim.setIsTicking(false);
		}
	}

	public byte[] getScreenshot(int width, int height) throws IOException {
		waitForStability(); // Ensure components have updated their visual state

		Circuit circuit = project.getCurrentCircuit();
		BufferedImage measureImage = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
		java.awt.Graphics measureGraphics = measureImage.getGraphics();

		// Prefer graphics-aware bounds from the original engine so text overhang and
		// label metrics match GUI/export behavior. Keep manual union as fallback.
		int xMin = Integer.MAX_VALUE;
		int yMin = Integer.MAX_VALUE;
		int xMax = Integer.MIN_VALUE;
		int yMax = Integer.MIN_VALUE;
		for (Component c : circuit.getComponents()) {
			Bounds b = c.getBounds();
			if (b == null || b == Bounds.EMPTY_BOUNDS)
				continue;
			if (b.getX() < xMin)
				xMin = b.getX();
			if (b.getY() < yMin)
				yMin = b.getY();
			if (b.getX() + b.getWidth() > xMax)
				xMax = b.getX() + b.getWidth();
			if (b.getY() + b.getHeight() > yMax)
				yMax = b.getY() + b.getHeight();
		}

		Bounds graphicsBounds = circuit.getBounds(measureGraphics);
		measureGraphics.dispose();

		Bounds bounds;
		if (graphicsBounds != null && graphicsBounds != Bounds.EMPTY_BOUNDS) {
			bounds = graphicsBounds;
			if (xMin != Integer.MAX_VALUE) {
				int ux0 = Math.min(bounds.getX(), xMin);
				int uy0 = Math.min(bounds.getY(), yMin);
				int ux1 = Math.max(bounds.getX() + bounds.getWidth(), xMax);
				int uy1 = Math.max(bounds.getY() + bounds.getHeight(), yMax);
				bounds = Bounds.create(ux0, uy0, ux1 - ux0, uy1 - uy0);
			}
		} else if (xMin == Integer.MAX_VALUE) {
			// Empty circuit
			bounds = Bounds.create(0, 0, 100, 100);
		} else {
			bounds = Bounds.create(xMin, yMin, xMax - xMin, yMax - yMin);
		}
		bounds = bounds.expand(60);

		BufferedImage img = canvas.renderToImage(bounds);
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		ImageIO.write(img, "png", baos);
		return baos.toByteArray();
	}

	/**
	 * Runs a specified number of simulation ticks.
	 * The session remains frozen (default state).
	 *
	 * @param tickCount number of ticks to execute. Must be > 0.
	 * @return number of ticks executed.
	 */
	public int runTick(int tickCount) {
		if (tickCount <= 0) {
			throw new IllegalArgumentException("tickCount must be > 0.");
		}
		Simulator sim = project.getSimulator();
		for (int i = 0; i < tickCount; i++) {
			sim.tick();
		}
		waitForStability();
		return tickCount;
	}

	private Value parseValue(String s, BitWidth width) throws IllegalArgumentException {
		if (s == null || s.trim().isEmpty()) {
			throw new IllegalArgumentException("Invalid value format: " + s);
		}
		s = s.trim().toLowerCase();

		if (s.equals("x") || s.equals("z")) {
			throw new IllegalArgumentException("Invalid value format: " + s);
		}

		try {
			long longVal;
			if (s.startsWith("0x")) {
				// Explicit hex format
				longVal = Long.parseLong(s.substring(2), 16);
			} else if (s.startsWith("0b")) {
				// Explicit binary format
				longVal = Long.parseLong(s.substring(2), 2);
			} else if (s.startsWith("0") && s.length() > 1) {
				// 0开头的都当八进制
				longVal = Long.parseLong(s.substring(1), 8);
			} else {
				// Decimal
				longVal = Long.parseLong(s);
			}
			return Value.createKnown(width, (int) longVal);
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("Invalid value format: " + s);
		}
	}

	@Override
	public void close() {
		if (project != null && project.getSimulator() != null) {
			project.getSimulator().setIsRunning(false);
		}
		project = null;
		logisimFile = null;
		canvas = null;
		componentCache.clear();
		inputComponentCache.clear();
		outputComponentCache.clear();
	}
}
