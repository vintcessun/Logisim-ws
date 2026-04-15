package com.cburch.logisim.headless;

import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.CircuitState;
import com.cburch.logisim.comp.ComponentDrawContext;
import com.cburch.logisim.data.Bounds;
import com.cburch.logisim.gui.generic.GridPainter;
import com.cburch.logisim.gui.main.Canvas;
import com.cburch.logisim.proj.Project;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.Collections;

/**
 * A headless adaptation of the Logisim Canvas for off-screen rendering.
 */
public class HeadlessCanvas extends Canvas {
	public HeadlessCanvas(Project proj) {
		super(proj);
		// Force headless mode properties if not already set
		System.setProperty("java.awt.headless", "true");
	}

	/**
	 * Renders the current circuit to a BufferedImage using specified dimensions.
	 */
	public BufferedImage renderToImage(int width, int height) {
		return renderToImage(Bounds.create(0, 0, width, height));
	}

	/**
	 * Renders a specific area of the current circuit to a BufferedImage.
	 * Bypasses the Canvas/CanvasPainter pipeline entirely to avoid clip/zoom/scroll
	 * interference. Instead, draws directly via circuit.draw(ComponentDrawContext).
	 */
	public BufferedImage renderToImage(Bounds bounds) {
		int width = Math.max(1, bounds.getWidth());
		int height = Math.max(1, bounds.getHeight());

		Project proj = getProject();
		Circuit circuit = proj.getCurrentCircuit();
		CircuitState circuitState = proj.getCircuitState();

		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g2d = image.createGraphics();

		try {
			g2d.setRenderingHint(
				RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g2d.setRenderingHint(
				RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

			// White background
			g2d.setColor(Color.WHITE);
			g2d.fillRect(0, 0, width, height);

			// Draw grid in image coordinates (same visual style as GUI background).
			g2d.setClip(0, 0, width, height);
			GridPainter gridPainter = new GridPainter(this);
			gridPainter.paintGrid(g2d);

			// Map circuit coordinate (bounds.getX(), bounds.getY()) → image pixel (0, 0)
			g2d.translate(-bounds.getX(), -bounds.getY());

			// Draw circuit directly — no Canvas/CanvasPainter/scroll/zoom involved
			ComponentDrawContext ctx =
				new ComponentDrawContext(this, circuit, circuitState, g2d, g2d);
			circuit.draw(ctx, Collections.emptySet());
		} finally {
			g2d.dispose();
		}

		return image;
	}
}
