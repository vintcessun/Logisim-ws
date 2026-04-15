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
import com.cburch.logisim.std.wiring.Pin;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.ImageIO;

/**
 * Manages the state and operations for a single Logisim session.
 */
public class LogisimSessionContext implements AutoCloseable {
	private Project project;
	private LogisimFile logisimFile;
	private HeadlessCanvas canvas;
	private Map<String, List<Component>> componentCache = new ConcurrentHashMap<>();
	private Map<String, List<Component>> inputComponentCache = new ConcurrentHashMap<>();
	private Map<String, List<Component>> outputComponentCache = new ConcurrentHashMap<>();

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
	}

	private void addToCache(Map<String, List<Component>> cache, String label, Component comp) {
		cache.computeIfAbsent(label, k -> new ArrayList<>()).add(comp);
	}

	private Component selectPreferredComponent(List<Component> candidates) {
		if (candidates == null || candidates.isEmpty())
			return null;

		for (Component comp : candidates) {
			String factoryName = comp.getFactory().getName();
			if (factoryName.equals("Button") || factoryName.equals("Pin")) {
				return comp;
			}
		}
		return candidates.get(0);
	}

	private Component findInCache(Map<String, List<Component>> cache, String target) {
		List<Component> candidates = cache.get(target);
		return selectPreferredComponent(candidates);
	}

	/**
	 * Fallback: if no component is found by label, searches the current circuit
	 * for components whose factory name equals {@code factoryName}.
	 * Returns the component if exactly one matches; throws if multiple match
	 * (ambiguous); returns {@code null} if none match.
	 */
	private Component findByUniqueName(String factoryName) {
		Circuit circuit = project.getCurrentCircuit();
		if (circuit == null)
			return null;
		List<Component> matches = new ArrayList<>();
		for (Component comp : circuit.getNonWires()) {
			if (comp.getFactory().getName().equals(factoryName)) {
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

	public Map<String, List<String>> getIO() {
		List<String> inputs = new ArrayList<>(inputComponentCache.keySet());
		List<String> outputs = new ArrayList<>(outputComponentCache.keySet());
		List<String> allLabeled = new ArrayList<>(componentCache.keySet());

		// Try to find single unlabeled components by factory name
		Circuit circuit = project.getCurrentCircuit();
		if (circuit != null) {
			Map<String, Integer> factoryCount = new ConcurrentHashMap<>();
			Map<String, Component> factoryExample = new ConcurrentHashMap<>();

			// Count unlabeled components by factory type
			for (Component comp : circuit.getNonWires()) {
				String label = (String) comp.getAttributeSet().getValue(StdAttr.LABEL);
				if (label == null || label.trim().isEmpty()) {
					String factoryName = comp.getFactory().getName();
					factoryCount.merge(factoryName, 1, Integer::sum);
					factoryExample.putIfAbsent(factoryName, comp);
				}
			}

			// For each factory type with exactly 1 unlabeled component, add it
			for (String factoryName : factoryCount.keySet()) {
				if (factoryCount.get(factoryName) == 1) {
					Component comp = factoryExample.get(factoryName);
					Boolean isOutput = (Boolean) comp.getAttributeSet().getValue(Pin.ATTR_TYPE);

					if (factoryName.equals("Pin")) {
						if (isOutput != null && isOutput) {
							outputs.add(factoryName);
						} else {
							inputs.add(factoryName);
						}
						allLabeled.add(factoryName);
					} else if (factoryName.equals("Button")) {
						inputs.add(factoryName);
						allLabeled.add(factoryName);
					} else {
						// For other component types (Clock, etc.), add to all_labeled
						allLabeled.add(factoryName);
					}
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
		return "0x" + val.toHexString();
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

		if (clock != null && !clock.isEmpty() && !inputComponentCache.containsKey(clock)) {
			throw new IllegalArgumentException("Clock component not found: " + clock);
		}

		BitWidth width = targetComp.getAttributeSet().getValue(StdAttr.WIDTH);
		if (width == null)
			width = BitWidth.ONE;

		// Preliminary check
		if (matches(getValue(target), expected, width))
			return 0;

		for (int i = 0; i < maxTicks; i++) {
			if (clock != null && !clock.isEmpty()) {
				// Pulse the clock manually (1 then 0)
				setValue(clock, "1");
				setValue(clock, "0");
			} else {
				// Advance internal simulation clock
				project.getSimulator().tick();
				waitForStability();
			}

			// getValue ensures stability and state consistency
			if (matches(getValue(target), expected, width)) {
				return i + 1; // Return 1-based step count
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
		if (!factoryName.equals("ROM"))
			throw new IllegalArgumentException("Component '" + label + "' is of type '"
				+ factoryName + "', not ROM. This load_memory API currently supports ROM only.");

		var contentsAttr = comp.getAttributeSet().getAttribute("contents");
		if (contentsAttr == null)
			throw new IllegalArgumentException(
				"Cannot access memory contents attribute of component '" + label + "'.");

		@SuppressWarnings({"rawtypes", "unchecked"})
		Object rawContents =
			comp.getAttributeSet().getValue((com.cburch.logisim.data.Attribute) contentsAttr);
		if (!(rawContents instanceof HexModel))
			throw new IllegalArgumentException(
				"Cannot access memory contents of component '" + label + "'.");
		HexModel contents = (HexModel) rawContents;

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
		project.getSimulator().requestPropagate();
		waitForStability();
	}

	/**
	 * Loads ROM contents from a txt file in Logisim hex format (v2.0 raw).
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
		if (!factoryName.equals("ROM")) {
			throw new IllegalArgumentException("Component '" + label + "' is of type '"
				+ factoryName + "', not ROM. This load_memory API currently supports ROM only.");
		}

		var contentsAttr = comp.getAttributeSet().getAttribute("contents");
		if (contentsAttr == null) {
			throw new IllegalArgumentException(
				"Cannot access memory contents attribute of component '" + label + "'.");
		}

		@SuppressWarnings({"rawtypes", "unchecked"})
		Object rawContents =
			comp.getAttributeSet().getValue((com.cburch.logisim.data.Attribute) contentsAttr);
		if (!(rawContents instanceof HexModel)) {
			throw new IllegalArgumentException(
				"Cannot access memory contents of component '" + label + "'.");
		}
		HexModel contents = (HexModel) rawContents;

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

		project.getSimulator().requestPropagate();
		waitForStability();
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

		// Compute bounding box by explicitly unioning every component's getBounds().
		// circuit.getBounds() has edge-case bugs (e.g. drops wire bounds when a
		// dimension is 0, and doesn't account for label overhang).
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

		Bounds bounds;
		if (xMin == Integer.MAX_VALUE) {
			// Empty circuit
			bounds = Bounds.create(0, 0, 100, 100);
		} else {
			bounds = Bounds.create(xMin, yMin, xMax - xMin, yMax - yMin).expand(30);
		}

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
