import com.airgaprx.core.kb.*;
import com.airgaprx.core.model.*;
import com.airgaprx.core.reasoning.*;
import java.util.*;
public class Probe {
  public static void main(String[] args) {
    var a = new Ingredient("a", "Drug A", IngredientKind.PRESCRIPTION, null);
    var b = new Ingredient("b", "Drug B", IngredientKind.PRESCRIPTION, null);
    var minor = new Interaction("a","b",Severity.MINOR,"Minor mechanism","Minor-record management","SOURCE-ONE",null);
    var major = new Interaction("a","b",Severity.MAJOR,"Major mechanism","Major-record management","SOURCE-TWO",null);
    var version = new KbVersion("probe","n/a","n/a",List.of(),2,2,"development",0,"unknown","");
    var kb = new InMemoryKnowledgeBase(List.of(a,b),List.of(),List.of(minor,major),version);
    var engine = new InteractionEngine(kb);
    var alert = engine.evaluate(List.of(new ShelfEntry(1L,a,"Product A"),new ShelfEntry(2L,b,"Product B"))).get(0);
    var explanation = new ExplanationGenerator().explain(alert,version);
    System.out.println("SEVERITY=" + alert.getSeverity());
    System.out.println("MANAGEMENT=" + explanation.getManagement());
    var sameName = engine.evaluate(List.of(new ShelfEntry(1L,a,"Pain relief"),new ShelfEntry(2L,a,"Pain relief")));
    var differentNames = engine.evaluate(List.of(new ShelfEntry(1L,a,"Pain relief A"),new ShelfEntry(2L,a,"Pain relief B")));
    System.out.println("TWO_ENTRIES_SAME_PRODUCT_NAME_ALERTS=" + sameName.size());
    System.out.println("TWO_ENTRIES_DIFFERENT_PRODUCT_NAMES_ALERTS=" + differentNames.size());
    System.out.println("MODEL_PATH_EXAMPLE=" + new java.io.File(new java.io.File("/data/user/0/com.airgaprx.app/files/models"),"../outside.task").toPath().normalize());
  }
}