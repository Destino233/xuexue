// Captures Swing L&F UI classes (JFileChooser, JOptionPane) under the
// tracing agent. Renders each dialog inside a JFrame for a few seconds
// (non-blocking, self-dismissing) so the agent records the reflection.
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.UIManager;
import javax.swing.SwingUtilities;

public class ChooserProbe {
    public static void main(String[] args) throws Exception {
        UIManager.setLookAndFeel("javax.swing.plaf.metal.MetalLookAndFeel");
        SwingUtilities.invokeAndWait(() -> {
            JFrame open = new JFrame("打开工作区");
            JFileChooser fc = new JFileChooser("D:\\test");
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            open.setContentPane(fc);
            open.setSize(900, 600);
            open.setLocation(120, 120);
            open.setVisible(true);
            try { Thread.sleep(4000); } catch (InterruptedException ignored) {}
            open.dispose();
            JFrame save = new JFrame("另存为");
            JFileChooser sc = new JFileChooser("D:\\test");
            sc.setFileSelectionMode(JFileChooser.FILES_ONLY);
            save.setContentPane(sc);
            save.setSize(900, 600);
            save.setLocation(160, 160);
            save.setVisible(true);
            try { Thread.sleep(4000); } catch (InterruptedException ignored) {}
            save.dispose();
            JOptionPane.showMessageDialog(new JFrame(), "probe error", "XueXue", JOptionPane.ERROR_MESSAGE);
        });
        Thread.sleep(1500);
        System.exit(0);
    }
}
