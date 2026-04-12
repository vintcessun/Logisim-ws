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
        if (state == null) return;
        Propagator prop = state.getPropagator();
        if (prop == null) return;

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
        if (circuit == null) return;

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
        if (circuit != null) {
            project.setCurrentCircuit(circuit);
            refreshComponentCache();
        }
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
        if (target == null || valueStr == null) return;
        Component comp = componentCache.get(target);
        if (comp == null) return;

        CircuitState state = project.getCircuitState();
        BitWidth width = comp.getAttributeSet().getValue(StdAttr.WIDTH);
        if (width == null) width = BitWidth.ONE;
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
        if (target == null) return "unknown";
        Component comp = componentCache.get(target);
        if (comp == null) return "unknown";

        waitForStability(); // Ensure stable state before reading
        CircuitState state = project.getCircuitState();
        Value val = state.getValue(comp.getLocation());
        return val.toHexString();
    }

    public int tickUntil(String target, String expected, String clock, int maxTicks) {
        Component targetComp = componentCache.get(target);
        if (targetComp == null) return -1;
        
        BitWidth width = targetComp.getAttributeSet().getValue(StdAttr.WIDTH);
        if (width == null) width = BitWidth.ONE;

        // Preliminary check
        if (matches(getValue(target), expected, width)) return 0;
        
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
        if (currentHex == null || expectedStr == null) return false;
        if (currentHex.equalsIgnoreCase(expectedStr)) return true;
        
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
        BufferedImage img = canvas.renderToImage(width, height);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        return baos.toByteArray();
    }

    private Value parseValue(String s, BitWidth width) {
        if (s.equalsIgnoreCase("x")) return Value.createUnknown(width);
        if (s.equalsIgnoreCase("z")) return Value.createUnknown(width); 
        
        try {
            long longVal;
            if (s.toLowerCase().startsWith("0x")) {
                longVal = Long.parseLong(s.substring(2), 16);
            } else {
                longVal = Long.parseLong(s);
            }
            return Value.createKnown(width, (int) longVal);
        } catch (NumberFormatException e) {
            return Value.createUnknown(width);
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
