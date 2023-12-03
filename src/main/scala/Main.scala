import org.apache.spark.SparkConf
import org.apache.spark.SparkContext
import org.apache.spark.graphx._
import org.apache.spark.graphx.Graph.graphToGraphOps
import org.apache.spark.rdd.RDD
import scala.reflect.ClassTag
import java.io.PrintWriter
object Main{
  def loadTheGraph(sc: SparkContext): Graph[(Int, Int, Long, String, String, Long, Int, String, Int), Long] = {
    //Read edges from the CSV file and parse them into Edge objects
    val edgesRDD: RDD[Edge[Long]] = sc.textFile("src/large_twitch_edges.csv")
      .filter(line => !line.startsWith("numeric_id_1"))
      .map { line =>
        val parts = line.split(",")
        Edge(parts(0).toLong, parts(1).toLong, 1)
      }

    val verticesRDD: RDD[(VertexId, (Int, Int, Long, String, String, Long, Int, String, Int))] = sc.textFile("src/large_twitch_features.csv")
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
        
    // Creat regular Graph
    val graph = Graph(verticesRDD, edgesRDD)
    return graph
  }


  def main(args: Array[String]) {
    val sparkConf = new SparkConf().setAppName("GraphFromFile").setMaster("local[*]")
    val sc = new SparkContext(sparkConf)

    val graph = loadTheGraph(sc)
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

    val louvainBasedGraph = Louvain.generateLouvainGraph(graph)
    val louvainBasedGraphCached = louvainBasedGraph.cache()

    val nodeDegrees = louvainBasedGraphCached.aggregateMessages[(Long, Long)](
    triplet => {
      // İç derece: Kendi düğümün kenar sayısı
      triplet.sendToSrc((1L, 0L))
      triplet.sendToDst((0L, 1L))
    },
    // İç ve dış dereceleri toplama işlemi
    (a, b) => (a._1 + b._1, a._2 + b._2)
    )
    val nodeIODegree: RDD[(Long, Long)] = nodeDegrees.map { case (vertexId, (innerDegree, outerDegree)) => (vertexId, innerDegree + outerDegree) }

    val m: Long = nodeIODegree.map { case (vertexId, degree) => degree }.sum().toLong
    var mBroadcasted = sc.broadcast(m)
    println(s"Toplam Edge Sayısı: $m")

  }
}
