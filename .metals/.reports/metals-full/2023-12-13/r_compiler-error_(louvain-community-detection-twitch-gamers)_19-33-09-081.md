file://<WORKSPACE>/src/main/scala/Main.scala
### java.lang.NullPointerException

occurred in the presentation compiler.

action parameters:
offset: 3384
uri: file://<WORKSPACE>/src/main/scala/Main.scala
text:
```scala
import org.apache.spark.SparkConf
import org.apache.spark.SparkContext
import org.apache.spark.graphx._
import org.apache.spark.graphx.Graph.graphToGraphOps
import org.apache.spark.rdd.RDD
import scala.reflect.ClassTag
import java.io.PrintWriter
import org.apache.spark.broadcast.Broadcast
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

    var minProgress = 1//parameters

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


    var communityRDD = louvainBasedGraphCached.aggregateMessages(sendCommunityInfo, mergeCommunityInfo).cache()
    var activeMessages = communityRDD.count() // materializes the graph

    var updated = 0L - minProgress
    var even = false  
    var count = 0
    val maxIter = 100000 
    var stop = 0
    var updatedLastPhase = 0L

    do{

    }while(true)

  }
  def louvain()@@


  def louvainVertJoin(louvainGraph: Graph[LouvainVertex, Long], msgRDD: VertexRDD[Map[(Long, Long), Long]], m: Broadcast[Long], even: Boolean) = {

    // innerJoin[U, VD2](other: RDD[(VertexId, U)])(f: (VertexId, VD, U) => VD2): VertexRDD[VD2]
    louvainGraph.vertices.innerJoin(msgRDD)((vid, louvainVertex, communityMessages) => {

      var bestCommunity = louvainVertex.communityType
      val startingCommunityId = bestCommunity
      var maxDeltaQ = BigDecimal(0.0);
      var bestSigmaTot = 0L

      // VertexRDD[scala.collection.immutable.Map[(Long, Long),Long]]
      // e.g. (1,Map((3,10) -> 2, (6,4) -> 2, (2,8) -> 2, (4,8) -> 2, (5,8) -> 2))
      // e.g. communityId:3, sigmaTotal:10, communityEdgeWeight:2
      communityMessages.foreach({ case ((communityId, sigmaTotal), communityEdgeWeight) =>
        val deltaQ = q(
          startingCommunityId,
          communityId,
          sigmaTotal,
          communityEdgeWeight,
          louvainVertex.degreeOfNode,
          louvainVertex.communityInDegree,
          m.value)

        //println(" communtiy: "+communityId+" sigma:"+sigmaTotal+"
        //edgeweight:"+communityEdgeWeight+" q:"+deltaQ)
        if (deltaQ > maxDeltaQ || (deltaQ > 0 && (deltaQ == maxDeltaQ &&
          communityId > bestCommunity))) {
          maxDeltaQ = deltaQ
          bestCommunity = communityId
          bestSigmaTot = sigmaTotal
        }
      })

      // only allow changes from low to high communties on even cyces and
      // high to low on odd cycles
      if (louvainVertex.communityType != bestCommunity && ((even &&
        louvainVertex.communityType > bestCommunity) || (!even &&
        louvainVertex.communityType < bestCommunity))) {
        //println("  "+vid+" SWITCHED from "+vdata.community+" to "+bestCommunity)
        louvainVertex.communityType = bestCommunity
        louvainVertex.communityType = bestSigmaTot
        louvainVertex.changed = true
      }
      else {
        louvainVertex.changed = false
      }

      if (louvainVertex == null)
        println("vdata is null: " + vid)

      louvainVertex
    })
  }

  /**
    * Returns the change in modularity that would result from a vertex
    * moving to a specified community.
    */
  def q(
         currCommunityId: Long,
         testCommunityId: Long,
         testSigmaTot: Long,
         edgeWeightInCommunity: Long,
         nodeWeight: Long,
         internalWeight: Long,
         totalEdgeWeight: Long): BigDecimal = {

    val isCurrentCommunity = currCommunityId.equals(testCommunityId)
    val M = BigDecimal(totalEdgeWeight)
    val k_i_in_L = if (isCurrentCommunity) edgeWeightInCommunity + internalWeight else edgeWeightInCommunity
    val k_i_in = BigDecimal(k_i_in_L)
    val k_i = BigDecimal(nodeWeight + internalWeight)
    val sigma_tot = if (isCurrentCommunity) BigDecimal(testSigmaTot) - k_i else BigDecimal(testSigmaTot)

    var deltaQ = BigDecimal(0.0)

    if (!(isCurrentCommunity && sigma_tot.equals(BigDecimal.valueOf(0.0)))) {
      deltaQ = k_i_in - (k_i * sigma_tot / M)
      //println(s"      $deltaQ = $k_i_in - ( $k_i * $sigma_tot / $M")
    }

    deltaQ
  }

  
  private def sendCommunityInfo(edgeTrip: EdgeContext[LouvainVertex, Long, Map[(Long, Long), Long]]) = {
    val m1 = (edgeTrip.dstId,Map((edgeTrip.srcAttr.communityType,edgeTrip.srcAttr.communityTotalDegree)->edgeTrip.attr))
	  val m2 = (edgeTrip.srcId,Map((edgeTrip.dstAttr.communityType,edgeTrip.dstAttr.communityTotalDegree)->edgeTrip.attr))
	  Iterator(m1, m2)    
  }
  
  
  
  /**
   *  Merge neighborhood community data into a single message for each vertex
   */
  private def mergeCommunityInfo(m1:Map[(Long,Long),Long],m2:Map[(Long,Long),Long]) ={
    val newMap = scala.collection.mutable.HashMap[(Long,Long),Long]()
    m1.foreach({case (k,v)=>
      if (newMap.contains(k)) newMap(k) = newMap(k) + v
      else newMap(k) = v
    })
    m2.foreach({case (k,v)=>
      if (newMap.contains(k)) newMap(k) = newMap(k) + v
      else newMap(k) = v
    })
    newMap.toMap
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
	scala.meta.internal.pc.ScalaPresentationCompiler.$anonfun$signatureHelp$1(ScalaPresentationCompiler.scala:282)
```
#### Short summary: 

java.lang.NullPointerException