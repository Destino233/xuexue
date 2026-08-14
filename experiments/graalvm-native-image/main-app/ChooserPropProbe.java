// Same as ChooserNativeProbe but WITHOUT the explicit setLookAndFeel call:
// relies solely on the -Dswing.defaultlaf property like the real app does.
import javax.swing.JFileChooser;
import javax.swing.UIManager;

public class ChooserPropProbe {
    public static void main(String[] args) throws Exception {
        System.out.println("P1 laf=" + UIManager.getLookAndFeel());
        Object v = UIManager.getLookAndFeelDefaults().get("FileChooserUI");
        System.out.println("P2 FileChooserUI=" + v);
        try {
            JFileChooser fc = new JFileChooser("D:\\test");
            System.out.println("P3 chooser OK " + fc);
        } catch (Throwable t) {
            System.out.println("P3 chooser FAIL " + t);
        }
        System.exit(0);
    }
}
