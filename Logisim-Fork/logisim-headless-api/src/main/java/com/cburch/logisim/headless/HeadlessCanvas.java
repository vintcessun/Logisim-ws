package com.cburch.logisim.headless;

import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.CircuitState;
import com.cburch.logisim.data.Bounds;
import com.cburch.logisim.gui.main.Canvas;
import com.cburch.logisim.prefs.AppPreferences;
import com.cburch.logisim.proj.Project;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;

/**
 * A headless adaptation of the Logisim Canvas for off-screen rendering.
 */
public class HeadlessCanvas extends Canvas {
	public HeadlessCanvas(Project proj) {
		super(proj);
		// Force headless mode properties if not already set
		System.setProperty("java.awt.headless", "true");
		// Match GUI startup: initialize Look&Feel before deriving default Swing fonts.
		AppPreferences.setLayout();
		SwingUtilities.updateComponentTreeUI(this);
		Font lafFont = new JLabel().getFont();
		if (lafFont != null) {
			setFont(lafFont);
		}
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
		int offsetX = Math.min(0, bounds.getX());
		int offsetY = Math.min(0, bounds.getY());
		int canvasWidth = Math.max(1, bounds.getX() + width - offsetX);
		int canvasHeight = Math.max(1, bounds.getY() + height - offsetY);

		Dimension oldSize = getSize();
		Dimension newSize = new Dimension(canvasWidth, canvasHeight);
		setSize(newSize);
		setPreferredSize(newSize);

		BufferedImage image =
			new BufferedImage(canvasWidth, canvasHeight, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();

		try {
			if (AppPreferences.ANTI_ALIASING.getBoolean()) {
				g.setRenderingHint(
					RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
				g.setRenderingHint(
					RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			}

			if (getFont() != null) {
				g.setFont(getFont());
			}
			g.setClip(0, 0, canvasWidth, canvasHeight);
			g.translate(-offsetX, -offsetY);
			// Use the original Canvas rendering pipeline for font/layout parity.
			printAll(g);
		} finally {
			g.dispose();
			setSize(oldSize);
			setPreferredSize(oldSize);
		}

		int cropX = bounds.getX() - offsetX;
		int cropY = bounds.getY() - offsetY;
		BufferedImage cropped = image.getSubimage(cropX, cropY, width, height);
		BufferedImage copy = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D copyGraphics = copy.createGraphics();
		try {
			copyGraphics.drawImage(cropped, 0, 0, null);
		} finally {
			copyGraphics.dispose();
		}
		return copy;
	}
}
