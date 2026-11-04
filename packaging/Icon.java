import java.awt.*; import java.awt.geom.*; import java.awt.image.BufferedImage; import javax.imageio.ImageIO; import java.io.File; import java.util.Random;
/** Margin app icon: paper tile, vermilion margin rule, ink brush branch. Usage: java packaging/Icon.java out.png */
public class Icon {
  static final Color INK = new Color(0x1B1A17), VERMILION = new Color(0xC8462B);

  public static void main(String[] a) throws Exception {
    int n = 1024; var img = new BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB); var g = img.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    // macOS grid: 824px rounded body centred, soft drop shadow
    for (int i = 18; i > 0; i--) { g.setColor(new Color(0, 0, 0, 6)); g.fill(new RoundRectangle2D.Double(100 - i/2.0, 112 - i/3.0, 824 + i, 824 + i, 190 + i, 190 + i)); }
    var body = new RoundRectangle2D.Double(100, 100, 824, 824, 185, 185);
    g.setPaint(new GradientPaint(0, 100, new Color(0xFBF8F2), 0, 924, new Color(0xECE5D7))); g.fill(body);
    g.setClip(body);
    var rnd = new Random(7); // paper grain, seeded so the icon is reproducible
    for (int i = 0; i < 9000; i++) { g.setColor(new Color(60, 45, 30, 4 + rnd.nextInt(6))); g.fill(new Ellipse2D.Double(100 + rnd.nextDouble() * 824, 100 + rnd.nextDouble() * 824, 2.2, 2.2)); }
    g.setColor(new Color(VERMILION.getRed(), VERMILION.getGreen(), VERMILION.getBlue(), 190)); g.fill(new Rectangle2D.Double(268, 100, 7, 824)); // the margin
    g.setClip(null);
    g.setStroke(new BasicStroke(3f)); g.setColor(new Color(0, 0, 0, 22)); g.draw(new RoundRectangle2D.Double(101.5, 101.5, 821, 821, 183, 183));
    // branch: trunk and a curve that thins as it joins, like one brush stroke
    double tx = 500, top = 300, bot = 724, bx = 720, by = 360;
    g.setColor(INK);
    brush(g, new Line2D.Double(tx, top, tx, bot), 30, 34, 30);
    var p = new Path2D.Double(); p.moveTo(bx, by); p.curveTo(bx, 560, tx, 520, tx, 650);
    brush(g, p, 30, 26, 16);
    g.fill(new Ellipse2D.Double(tx - 58, top - 58, 116, 116));
    g.fill(new Ellipse2D.Double(tx - 58, bot - 58, 116, 116));
    g.setColor(VERMILION); g.fill(new Ellipse2D.Double(bx - 58, by - 58, 116, 116));
    ImageIO.write(img, "png", new File(a[0]));
  }

  /** Stamps discs along [s] with a radius going start, middle, end (quadratic), so the stroke tapers like ink. */
  static void brush(Graphics2D g, Shape s, double r0, double r1, double r2) {
    var pts = new java.util.ArrayList<double[]>();
    var it = new FlatteningPathIterator(s.getPathIterator(null), 0.5); double[] c = new double[6];
    while (!it.isDone()) { it.currentSegment(c); pts.add(new double[]{c[0], c[1]}); it.next(); }
    double total = 0; for (int i = 1; i < pts.size(); i++) total += pts.get(i).length == 0 ? 0 : Point2D.distance(pts.get(i-1)[0], pts.get(i-1)[1], pts.get(i)[0], pts.get(i)[1]);
    double done = 0;
    for (int i = 1; i < pts.size(); i++) {
      double[] q = pts.get(i - 1), e = pts.get(i); double len = Point2D.distance(q[0], q[1], e[0], e[1]);
      for (double d = 0; d < len; d += 1.5) {
        double t = (done + d) / total, x = q[0] + (e[0] - q[0]) * d / len, y = q[1] + (e[1] - q[1]) * d / len;
        double r = (1 - t) * (1 - t) * r0 + 2 * (1 - t) * t * r1 + t * t * r2;
        g.fill(new Ellipse2D.Double(x - r, y - r, 2 * r, 2 * r));
      }
      done += len;
    }
  }
}
