import org.apache.spark.SparkConf
import org.apache.spark.SparkContext
import org.apache.spark.graphx._
import org.apache.spark.graphx.Graph.graphToGraphOps
import org.apache.spark.rdd.RDD
import scala.reflect.ClassTag
import java.io.PrintWriter
object Main extends App {
  
    val sparkConf = new SparkConf().setAppName("GraphFromFile").setMaster("local[*]")
    val sc = new SparkContext(sparkConf)

    //Read edges from the CSV file and parse them into Edge objects
    val edgesRDD: RDD[Edge[Int]] = sc.textFile("src/sampleEdges.csv")
      .filter(line => !line.startsWith("numeric_id_1"))
      .map { line =>
        val parts = line.split(",")
        Edge(parts(0).toLong, parts(1).toLong, 1)
      }

    val verticesRDD: RDD[(VertexId, (Int, Int, Long, String, String, Long, Int, String, Int))] = sc.textFile("src/sampleFeatures.csv")
      .filter(line => !line.startsWith("views"))
      .map { line =>
        val parts = line.split(",")
        (
          parts(5).toLong,        // numeric_id as VertexId
          (
            parts(0).toInt,       // views
            parts(1).toInt,       // mature  
            parts(2).toLong,      // life_time
            parts(3),             // created at
            parts(4),             // updated_Ad
            parts(5).toLong,      // numeric_id (repeated)
            parts(6).toInt,       // dead_account
            parts(7),             // language
            parts(8).toInt        // affiliate
          )
        )
      }
      
    // Graph'ı oluşturma
      val graph = Graph(verticesRDD, edgesRDD)
      //val graph = Graph.fromEdges(edgesRDD, None)
  /*// Düğümleri (vertices) yazdırma
  println("Düğümler (Vertices):")
  graph.vertices.collect().foreach { case (id, vertex) =>
    println(s"ID: $id, Vertex: $vertex")
  }*/

  // Kenarları (edges) yazdırma
  /*println("Kenarlar (Edges):")
  graph.edges.collect().foreach { edge =>
    println(s"Kaynak: ${edge.srcId}, Hedef: ${edge.dstId}, Kenar: ${edge.attr}")
  }*/
   

  // Her bir düğüm için iç ve dış dereceleri hesaplayan fonksiyon
  val nodeDegrees = graph.aggregateMessages[(Long, Long)](
    triplet => {
      // İç derece: Kendi düğümün kenar sayısı
      triplet.sendToSrc((1L, 0L))
      triplet.sendToDst((0L, 1L))
    },
    // İç ve dış dereceleri toplama işlemi
    (a, b) => (a._1 + b._1, a._2 + b._2)
  )

  // Her bir düğümün iç ve dış derecelerini yazdırma
  nodeDegrees.foreach { case (vertexId, (innerDegree, outerDegree)) =>
    println(s"Düğüm $vertexId'nin iç derecesi: $innerDegree, dış derecesi: $outerDegree")
  }

   // Inner ve outer edge sayılarını toplamak
  val nodeIODegree: RDD[(Long, Long)] = nodeDegrees.map { case (vertexId, (innerDegree, outerDegree)) => (vertexId, innerDegree + outerDegree) }//2 ile çarpılabilabilir

  // Her bir düğümün iç ve dış derecelerini yazdırma
  nodeIODegree.foreach { case (vertexId, degree) =>
    println(s"Düğüm $vertexId'nin derecesi: $degree")
  }

  val totalEdges: Long = nodeIODegree.map { case (vertexId, degree) => degree }.sum().toLong


  // Sonuçları yazdırmak
  println(s"Toplam Edge Sayısı: $totalEdges")
}
