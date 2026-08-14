// Native-image diagnostic: where exactly does Swing break in the image?
import javax.swing.JFileChooser;
import javax.swing.UIManager;

public class ChooserNativeProbe {
    public static void main(String[] args) throws Exception {
        System.out.println("STEP1 laf=" + UIManager.getLookAndFeel());
        System.out.println("STEP2 defaults=" + UIManager.getLookAndFeelDefaults());
        Object v = UIManager.getLookAndFeelDefaults().get("FileChooserUI");
        System.out.println("STEP3 FileChooserUI=" + v);
        try {
            Class<?> c = Class.forName("javax.swing.plaf.metal.MetalFileChooserUI");
            System.out.println("STEP4 forName OK " + c);
        } catch (Throwable t) {
            System.out.println("STEP4 forName FAIL " + t);
        }
        try {
            JFileChooser fc = new JFileChooser("D:\\test");
            System.out.println("STEP5 chooser OK " + fc);
        } catch (Throwable t) {
            System.out.println("STEP5 chooser FAIL " + t);
        }
        System.exit(0);
    }
}
