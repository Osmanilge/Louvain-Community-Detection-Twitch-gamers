file://<WORKSPACE>/src/main/scala/ProcessData.scala
### java.lang.NullPointerException

occurred in the presentation compiler.

action parameters:
offset: 65
uri: file://<WORKSPACE>/src/main/scala/ProcessData.scala
text:
```scala
import scala.io.Source
object ProcessData {
    def loadAsTable()@@
    def main(args: Array[String]) {
    // Dosya yolu
    val dosyaYolu = "src/level_0_vertices/part-00000"

    // Dosyayı aç ve satırları oku
    val kaynak = Source.fromFile(dosyaYolu)
    val satirlar = kaynak.getLines().toList
    kaynak.close()

    // Satırları işle
    val desen = """\((\d+),\{Type of Community:(\d+),TotalDegreeOfCommunity:([\d.]+),inDegreeOfCommunity:([\d.]+),degreeOfNode:([\d.]+)\}\)""".r
    val tablo = satirlar.flatMap {
        case desen(id, topluluk, toplamDerece, inDerece, nodeDerece) =>
        Some((id.toInt, topluluk.toInt, toplamDerece.toDouble, inDerece.toDouble, nodeDerece.toDouble))
        case _ => None
    }

    // Oluşturulan tabloyu ekrana yazdır
    tablo.foreach(println)           

    
    }
}

```



#### Error stacktrace:

```
scala.reflect.internal.Definitions$DefinitionsClass.isByNameParamType(Definitions.scala:417)
	scala.reflect.internal.TreeInfo.isStableIdent(TreeInfo.scala:140)
	scala.reflect.internal.TreeInfo.isStableIdentifier(TreeInfo.scala:113)
	scala.reflect.internal.TreeInfo.isPath(TreeInfo.scala:102)
	scala.reflect.internal.TreeInfo.admitsTypeSelection(TreeInfo.scala:158)
	scala.tools.nsc.interactive.Global.stabilizedType(Global.scala:960)
	scala.tools.nsc.interactive.Global.typedTreeAt(Global.scala:808)
	scala.meta.internal.pc.SignatureHelpProvider.signatureHelp(SignatureHelpProvider.scala:23)
	scala.meta.internal.pc.ScalaPresentationCompiler.$anonfun$signatureHelp$1(ScalaPresentationCompiler.scala:300)
```
#### Short summary: 

java.lang.NullPointerException