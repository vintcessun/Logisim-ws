package com.cburch.logisim.headless;

import com.cburch.logisim.data.Bounds;
import com.cburch.logisim.gui.main.Canvas;
import com.cburch.logisim.proj.Project;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

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
     */
    public BufferedImage renderToImage(Bounds bounds) {
        int width = Math.max(1, bounds.getWidth());
        int height = Math.max(1, bounds.getHeight());
        
        // Manually set size and layout as there is no parent container
        this.setSize(width, height);
        this.setPreferredSize(new Dimension(width, height));
        this.doLayout();

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = image.createGraphics();
        
        try {
            // High quality rendering hints
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

            // Shift coordinate system so that (bounds.getX(), bounds.getY()) becomes (0, 0)
            g2d.translate(-bounds.getX(), -bounds.getY());

            // Use printAll to force painting the entire component hierarchy
            this.printAll(g2d);
        } finally {
            g2d.dispose();
        }
        
        return image;
    }
}

