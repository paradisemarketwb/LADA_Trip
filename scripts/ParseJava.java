import javax.tools.*;
import com.sun.source.util.JavacTask;
import java.nio.file.*;
import java.util.*;
public class ParseJava {
  public static void main(String[] args) throws Exception {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    DiagnosticCollector<JavaFileObject> diags = new DiagnosticCollector<>();
    try (StandardJavaFileManager fm = compiler.getStandardFileManager(diags,null,null)) {
      List<Path> paths=new ArrayList<>();
      try(var stream=Files.walk(Path.of(args[0]))){stream.filter(p->p.toString().endsWith(".java") && !p.getFileName().toString().equals("ParseJava.java")).forEach(paths::add);}
      JavacTask task=(JavacTask)compiler.getTask(null,fm,diags,List.of("-proc:none"),null,fm.getJavaFileObjectsFromPaths(paths));
      task.parse();
      int errors=0;
      for(var d:diags.getDiagnostics())if(d.getKind()==Diagnostic.Kind.ERROR){System.err.println(d);errors++;}
      System.out.println("Java syntax parsed: "+paths.size()+" files; syntax errors: "+errors);
      if(errors>0)System.exit(1);
    }
  }
}
