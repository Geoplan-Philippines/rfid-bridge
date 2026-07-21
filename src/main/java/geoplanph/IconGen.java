package geoplanph;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Build-time helper: renders the deep-blue "RB" app tile and writes it as a
 * Windows .ico (a single PNG-compressed 256x256 entry, supported since Vista)
 * so jpackage can brand the generated .exe. Not used at runtime.
 *
 * Usage: java -cp out geoplanph.IconGen <output.ico>
 */
public final class IconGen {

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args.length > 0 ? args[0] : "assets/app.ico");
        Files.createDirectories(out.getParent() == null ? Path.of(".") : out.getParent());

        int size = 256;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        int pad = 18, arc = 44;
        g.setColor(new Color(0x0B, 0x4E, 0xA2));
        g.fill(new RoundRectangle2D.Float(pad, pad, size - 2 * pad, size - 2 * pad, arc, arc));
        // subtle cyan underline accent
        g.setColor(new Color(0x22, 0xD3, 0xEE));
        g.fillRoundRect(pad + 40, size - pad - 46, size - 2 * pad - 80, 12, 8, 8);
        g.setColor(Color.WHITE);
        g.setFont(new Font("Segoe UI", Font.BOLD, 128));
        var fm = g.getFontMetrics();
        String s = "RB";
        int tx = (size - fm.stringWidth(s)) / 2;
        int ty = (size - fm.getHeight()) / 2 + fm.getAscent() - 12;
        g.drawString(s, tx, ty);
        g.dispose();

        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(img, "png", png);
        byte[] pngBytes = png.toByteArray();

        try (OutputStream os = Files.newOutputStream(out)) {
            // ICONDIR
            writeLE16(os, 0);   // reserved
            writeLE16(os, 1);   // type = icon
            writeLE16(os, 1);   // image count
            // ICONDIRENTRY
            os.write(0);        // width  (0 => 256)
            os.write(0);        // height (0 => 256)
            os.write(0);        // color count
            os.write(0);        // reserved
            writeLE16(os, 1);   // color planes
            writeLE16(os, 32);  // bits per pixel
            writeLE32(os, pngBytes.length); // size of PNG data
            writeLE32(os, 22);  // offset (6 + 16)
            os.write(pngBytes);
        }
        System.out.println("Wrote icon: " + out.toAbsolutePath() + " (" + pngBytes.length + " bytes PNG)");
    }

    private static void writeLE16(OutputStream os, int v) throws Exception {
        os.write(v & 0xff); os.write((v >>> 8) & 0xff);
    }

    private static void writeLE32(OutputStream os, int v) throws Exception {
        os.write(v & 0xff); os.write((v >>> 8) & 0xff);
        os.write((v >>> 16) & 0xff); os.write((v >>> 24) & 0xff);
    }
}
