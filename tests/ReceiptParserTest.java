import com.labelscanner.offline.ArrivalGeometry;
import java.util.Arrays;
import java.util.Collections;

public class ReceiptParserTest {
    private static void eq(String a,String b){if(!java.util.Objects.equals(a,b))throw new AssertionError(a+" != "+b);}
    public static void main(String[] args){
        eq("102790", ArrivalGeometry.find(Arrays.asList(
            new ArrivalGeometry.Word("Приход №",5,90,105,120),
            new ArrivalGeometry.Word("102790",130,83,220,128),
            new ArrivalGeometry.Word("от",228,92,245,110),
            new ArrivalGeometry.Word("04.08.2025",250,85,370,110))));
        eq("91843", ArrivalGeometry.find(Arrays.asList(
            new ArrivalGeometry.Word("Приход №",6,35,136,68),
            new ArrivalGeometry.Word("91843",195,29,307,73),
            new ArrivalGeometry.Word("от",325,34,349,66),
            new ArrivalGeometry.Word("01.02.2024",357,32,499,69))));
        eq("91843", ArrivalGeometry.find(Arrays.asList(
            new ArrivalGeometry.Word("Приход",6,35,100,68),
            new ArrivalGeometry.Word("№",104,35,136,68),
            new ArrivalGeometry.Word("91843",289,29,380,73))));
        eq("1234", ArrivalGeometry.find(Arrays.asList(
            new ArrivalGeometry.Word("Приход №",5,20,105,47),
            new ArrivalGeometry.Word("1234",140,20,210,46))));
        eq("1234567", ArrivalGeometry.find(Arrays.asList(
            new ArrivalGeometry.Word("NPUXOD",5,20,105,47),
            new ArrivalGeometry.Word("1234567",140,20,240,47))));
        eq(null, ArrivalGeometry.find(Arrays.asList(new ArrivalGeometry.Word("Кат №",5,20,105,47),new ArrivalGeometry.Word("38196",140,20,240,47))));
        eq(null, ArrivalGeometry.find(Arrays.asList(new ArrivalGeometry.Word("Приход №",5,20,105,47),new ArrivalGeometry.Word("55555",140,230,240,250))));
        eq(null, ArrivalGeometry.find(Collections.singletonList(new ArrivalGeometry.Word("91843",195,29,307,73))));
        eq("91843", ArrivalGeometry.fromRussianOcr("Приход № | 91843 | от | 01.02.2024"));
        eq("102790", ArrivalGeometry.fromRussianOcr("Приход № 102790 от 04.08.2025"));
        eq(null, ArrivalGeometry.fromRussianOcr("Кат № 38196 Серия 06-03"));
        System.out.println("OK: arrival label, 4–7 digits, two label layouts, no unrelated numbers");
    }
}
