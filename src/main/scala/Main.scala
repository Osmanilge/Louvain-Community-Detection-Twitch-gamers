import org.apache.spark.SparkConf
import org.apache.spark.SparkContext
import org.apache.spark.graphx._
import org.apache.spark.graphx.Graph.graphToGraphOps
import org.apache.spark.rdd.RDD
import scala.reflect.ClassTag
import java.io.PrintWriter
import org.apache.spark.broadcast.Broadcast
object Main{
  def loadTheGraph(sc: SparkContext): Graph[(Int, Int, Long, String, String, Long, Int, String, Int), Double] = {
    //Read edges from the CSV file and parse them into Edge objects
    val edgesRDD: RDD[Edge[Double]] = sc.textFile("src/sampleEdges.csv")
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

    var louvainBasedGraph = Louvain.generateLouvainGraph(graph)

    var minProgress = 1//parameters
    var progressCounter = 1//parameters

    /*// label each vertex with its best community choice at this level of compression
	  val (currentQ,currentGraph,passes) = Louvain.louvain(sc, louvainBasedGraph,minProgress,progressCounter)
	  louvainBasedGraph.unpersistVertices(blocking=false)
	  louvainBasedGraph=currentGraph*/

    var level = -1  // number of times the graph has been compressed
    var q = -1.0    // current modularity value
    var halt = false
    do {
	  level += 1
	  println(s"\nStarting Louvain level $level")
	  
	  // label each vertex with its best community choice at this level of compression
	  val (currentQ,currentGraph,passes) = Louvain.louvain(sc, louvainBasedGraph,minProgress,progressCounter)
	  louvainBasedGraph.unpersistVertices(blocking=false)
	  louvainBasedGraph=currentGraph
	  
	  saveLevel(sc,level,currentQ,louvainBasedGraph)
	  
	  // If modularity was increased by at least 0.001 compress the graph and repeat
	  // halt immediately if the community labeling took less than 3 passes
	  //println(s"if ($passes > 2 && $currentQ > $q + 0.001 )")
	  if (passes > 2 && currentQ > q + 0.001 ){ 
	    q = currentQ
	    louvainBasedGraph = Louvain.compressGraph(louvainBasedGraph)
	  }
	  else {
	    halt = true
	  }
	 
	}while ( !halt )
	//finalSave(sc,level,q,louvainGraph)  
  
    
  }
  def saveLevel(sc:SparkContext,level:Int,q:Double,graph:Graph[LouvainVertex,Double]) = {
    var qValues = Array[(Int,Double)]()
    val outputdir = "src/"
	  graph.vertices.saveAsTextFile(outputdir+"/level_"+level+"_vertices")
      graph.edges.saveAsTextFile(outputdir+"/level_"+level+"_edges")
      //graph.vertices.map( {case (id,v) => ""+id+","+v.internalWeight+","+v.community }).saveAsTextFile(outputdir+"/level_"+level+"_vertices")
      //graph.edges.mapValues({case e=>""+e.srcId+","+e.dstId+","+e.attr}).saveAsTextFile(outputdir+"/level_"+level+"_edges")  
      qValues = qValues :+ ((level,q))
      println(s"qValue: $q")
        
      // overwrite the q values at each level
      sc.parallelize(qValues, 1).saveAsTextFile(outputdir+"/qvalues")
  }
}

