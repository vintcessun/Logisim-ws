package com.cburch.logisim.headless;

import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.CircuitState;
import com.cburch.logisim.circuit.Simulator;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.data.BitWidth;
import com.cburch.logisim.data.Value;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.instance.Instance;
import com.cburch.logisim.instance.InstanceState;
import com.cburch.logisim.instance.StdAttr;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.std.wiring.Pin;
import com.cburch.logisim.circuit.SimulatorListener;
import com.cburch.logisim.circuit.SimulatorEvent;
import com.cburch.logisim.circuit.Propagator;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import com.cburch.logisim.data.Bounds;
import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.awt.image.BufferedImage;

/**
 * Manages the state and operations for a single Logisim session.
 */
public class LogisimSessionContext implements AutoCloseable {
    private Project project;
    private LogisimFile logisimFile;
    private HeadlessCanvas canvas;
    private Map<String, Component> componentCache = new ConcurrentHashMap<>();

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
        Loader loader = new Loader(null);
        File file = new File(circPath);
        if (!file.exists()) {
            throw new IOException("File not found: " + circPath);
        }

        this.logisimFile = LogisimFile.load(file, loader);
        this.project = new Project(logisimFile);

        // Re-enable background simulation to support visual updates (brightness, LEDs)
        // But we will use stabilityListener to wait for completion in API calls.
        Simulator sim = project.getSimulator();
        sim.addSimulatorListener(stabilityListener);
        sim.setIsRunning(true);
        sim.setIsTicking(true);

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
        Circuit circuit = project.getCurrentCircuit();
        if (circuit == null)
            return;

        // First pass: prefer Pins (Input/Output)
        for (Component comp : circuit.getNonWires()) {
            String label = comp.getAttributeSet().getValue(StdAttr.LABEL);
            if (label != null && !label.trim().isEmpty()) {
                String lbl = label.trim();
                boolean isPin = comp.getFactory().getName().equals("Pin");
                if (isPin || !componentCache.containsKey(lbl)) {
                    componentCache.put(lbl, comp);
                }
            }
        }
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
        List<String> inputs = new ArrayList<>();
        List<String> outputs = new ArrayList<>();

        Circuit circuit = project.getCurrentCircuit();
        if (circuit != null) {
            for (Component comp : circuit.getNonWires()) {
                String label = comp.getAttributeSet().getValue(StdAttr.LABEL);
                if (label != null && !label.trim().isEmpty()) {
                    String factoryName = comp.getFactory().getName();
                    if (factoryName.equals("Pin")) {
                        Boolean isOutput = (Boolean) comp.getAttributeSet().getValue(Pin.ATTR_TYPE);
                        if (isOutput != null && isOutput) {
                            outputs.add(label.trim());
                        } else {
                            inputs.add(label.trim());
                        }
                    }
                }
            }
        }

        Map<String, List<String>> res = new ConcurrentHashMap<>();
        res.put("inputs", inputs);
        res.put("outputs", outputs);
        res.put("all_labeled", new ArrayList<>(componentCache.keySet()));
        return res;
    }

    public void setValue(String target, String valueStr) {
        if (target == null || valueStr == null) {
            throw new IllegalArgumentException("Target and value must not be null.");
        }
        Component comp = componentCache.get(target);
        if (comp == null) {
            throw new IllegalArgumentException("Component not found: " + target);
        }

        // Validation: Only input pins can be set
        if ((!comp.getFactory().getName().equals("Pin")) && (!comp.getFactory().getName().equals("Button"))) {
            throw new IllegalArgumentException(
                    "Component '" + target + "(" + comp.getFactory().getName() + ")' is not a Pin.");
        }
        Boolean isOutput = (Boolean) comp.getAttributeSet().getValue(Pin.ATTR_TYPE);
        if (isOutput != null && isOutput) {
            throw new IllegalArgumentException("Pin '" + target + "' is an output pin and cannot be changed.");
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
        Component comp = componentCache.get(target);
        if (comp == null) {
            throw new IllegalArgumentException("Component not found: " + target);
        }

        waitForStability(); // Ensure stable state before reading
        CircuitState state = project.getCircuitState();
        Value val = state.getValue(comp.getLocation());
        return val.toHexString();
    }

    public int tickUntil(String target, String expected, String clock, int maxTicks) {
        if (target == null) {
            throw new IllegalArgumentException("Target must not be null.");
        }
        Component targetComp = componentCache.get(target);
        if (targetComp == null) {
            throw new IllegalArgumentException("Component not found: " + target);
        }

        if (clock != null && !clock.isEmpty() && !componentCache.containsKey(clock)) {
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

    public byte[] getScreenshot(int width, int height) throws IOException {
        waitForStability(); // Ensure components have updated their visual state

        Circuit circuit = project.getCurrentCircuit();
        Bounds bounds = circuit.getBounds();
        if (bounds == null || bounds == Bounds.EMPTY_BOUNDS) {
            // If the circuit is empty, render a minimal area
            bounds = Bounds.create(0, 0, 100, 100);
        } else {
            // Automatically crop with 30px padding
            bounds = bounds.expand(30);
        }

        BufferedImage img = canvas.renderToImage(bounds);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        return baos.toByteArray();
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
                longVal = Long.parseLong(s.substring(2), 16);
            } else if (s.startsWith("0b")) {
                longVal = Long.parseLong(s.substring(2), 2);
            } else if (s.startsWith("0") && s.length() > 1) {
                longVal = Long.parseLong(s.substring(1), 8);
            } else {
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
    }
}
