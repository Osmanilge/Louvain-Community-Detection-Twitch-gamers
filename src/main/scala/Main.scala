import org.apache.spark.SparkConf
import org.apache.spark.SparkContext
import org.apache.spark.graphx.{Graph, VertexId, VertexRDD, Edge}
import org.apache.spark.rdd.RDD
import java.io.PrintWriter
object Main extends App {
  
    val sparkConf = new SparkConf().setAppName("GraphFromFile").setMaster("local[*]")
    val sc = new SparkContext(sparkConf)

    //Read edges from the CSV file and parse them into Edge objects
    val edgesRDD: RDD[Edge[Int]] = sc.textFile("/home/osmanilge/Desktop/Louvain-Community-Detection-Twitch-gamers/src/large_twitch_edges.csv")
      .filter(line => !line.startsWith("numeric_id_1"))
      .map { line =>
        val parts = line.split(",")
        Edge(parts(0).toLong, parts(1).toLong, 1)
      }

    val verticesRDD: RDD[(VertexId, (Int, Int, Long, String, String, Long, Int, String, Int))] = sc.textFile("/home/osmanilge/Desktop/Louvain-Community-Detection-Twitch-gamers/src/large_twitch_features.csv")
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

}