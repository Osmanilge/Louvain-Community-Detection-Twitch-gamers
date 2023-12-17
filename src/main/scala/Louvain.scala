import org.apache.spark.graphx._
import org.apache.spark.graphx.Graph.graphToGraphOps
import org.apache.spark.rdd.RDD
import scala.reflect.ClassTag
import org.apache.spark.SparkContext
import org.apache.spark.broadcast.Broadcast

object Louvain {
  
  def generateLouvainGraph[VD: ClassTag](graph: Graph[VD,Double]): Graph[LouvainVertex, Double] = {
    val nodeDegrees = graph.aggregateMessages[(Long, Long)](
    triplet => {
      // İç derece: Kendi düğümün kenar sayısı
      triplet.sendToSrc((1L, 0L))
      triplet.sendToDst((0L, 1L))
    },
    // İç ve dış dereceleri toplama işlemi
    (a, b) => (a._1 + b._1, a._2 + b._2)
    )
    val nodeIODegree: RDD[(Long, Long)] = nodeDegrees.map { case (vertexId, (innerDegree, outerDegree)) => (vertexId, innerDegree + outerDegree) }//2 ile çarpılabilabilir

    val louvainBasedGraph = graph.outerJoinVertices(nodeIODegree)((vertexID,VD1,degreeOption)=> { 
      val degree = degreeOption.getOrElse(0L)
      val LouvainVertex = new LouvainVertex()
      LouvainVertex.communityType = vertexID
      LouvainVertex.changed = false
      LouvainVertex.communityTotalDegree = degree
      LouvainVertex.communityInDegree = 0L
      LouvainVertex.degreeOfNode = degree
      LouvainVertex
    }).partitionBy(PartitionStrategy.EdgePartition2D).groupEdges(_+_)

    return louvainBasedGraph
    
  }

  def louvain(sc: SparkContext, louvainBasedGraph: Graph[LouvainVertex,Double], minProgress:Int = 1, progressCounter:Int=1) : (Double,Graph[LouvainVertex,Double],Int)= {
    var louvainBasedGraphCached = louvainBasedGraph.cache()

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

    val m: Double = nodeIODegree.map { case (vertexId, degree) => degree }.sum()
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

    do {
        count += 1
        even = !even

        // Her düğümü, komşu topluluk bilgilerine dayanarak en iyi topluluğu ile etiketler
        val labeledVerts = louvainVertJoin(louvainBasedGraphCached, communityRDD, mBroadcasted, even).cache()

        // Her topluluğun yeni sigma toplam değerini hesaplar
        val communityUpdate = labeledVerts
            .map({ case (vid, louvainVertex) => (louvainVertex.communityType, louvainVertex.degreeOfNode + louvainVertex.communityInDegree) })
            .reduceByKey(_ + _).cache()

        // Her düğümü, güncellenmiş topluluk bilgisi ile eşleştirir
        val communityMapping = labeledVerts
            .map({ case (vid, louvainVertex) => (louvainVertex.communityType, vid) })
            .join(communityUpdate)
            .map({ case (community, (vid, sigmaTot)) => (vid, (community, sigmaTot)) })
            .cache()

        // Etiketlenmiş düğümleri, güncellenmiş topluluk bilgisi ile birleştirir
        val updatedVerts = labeledVerts.join(communityMapping).map({ case (vid, (louvainVertex, communityTuple)) =>
            louvainVertex.communityType = communityTuple._1
            louvainVertex.communityTotalDegree = communityTuple._2
            (vid, louvainVertex)
        }).cache()

        updatedVerts.count()
        labeledVerts.unpersist(blocking = false)
        communityUpdate.unpersist(blocking = false)
        communityMapping.unpersist(blocking = false)

        val prevG = louvainBasedGraphCached
        louvainBasedGraphCached = louvainBasedGraphCached.outerJoinVertices(updatedVerts)((vid, old, newOpt) => newOpt.getOrElse(old))
        louvainBasedGraphCached.cache()

        // Her düğümün yerel komşuluk bilgisinden topluluk bilgisini toplar
        val oldMsgs = communityRDD
        communityRDD = louvainBasedGraphCached.aggregateMessages(sendCommunityInfo, mergeCommunityInfo).cache()
        activeMessages = communityRDD.count()

        oldMsgs.unpersist(blocking = false)
        updatedVerts.unpersist(blocking = false)
        prevG.unpersistVertices(blocking = false)

        // Her iki çevrimde yarısı topluluk değişebilir
        // ve diğer yarısı tek çevrimlerde değişebilir (ölümcül durumları önlemek için)
        // bu nedenle yalnızca tek çevrimlerde ilerleme aramak istiyoruz (tüm düğümler şansı olmadan önce)
        if (even) updated = 0
        updated = updated + louvainBasedGraphCached.vertices.filter(_._2.changed).count
        if (!even) {
            println("  # vertices moved: " + java.text.NumberFormat.getInstance().format(updated))
            if (updated >= updatedLastPhase - minProgress) stop += 1
            updatedLastPhase = updated
        }

    } while (stop <= progressCounter && (even || (updated > 0 && count < maxIter)))
    if (communityRDD.isEmpty()) {
      // Handle empty RDD case
      print("comRdd boş\n")
    } 
    // Use each vertex's neighboring community data to calculate the global modularity of the graph
    val newVerts = louvainBasedGraphCached.vertices.innerJoin(communityRDD)((vid,vdata,msgs)=> {
        // sum the nodes internal weight and all of its edges that are in its community
        val community = vdata.communityType
        var k_i_in = vdata.communityInDegree
        var sigmaTot = vdata.communityTotalDegree.toDouble
        msgs.foreach({ case( (communityId,sigmaTotal),communityEdgeWeight ) => 
          if (vdata.communityType == communityId) k_i_in += communityEdgeWeight})
        val M = mBroadcasted.value
        val k_i = vdata.degreeOfNode + vdata.communityInDegree
        var q = (k_i_in.toDouble / M) -  ( ( sigmaTot *k_i) / math.pow(M, 2) )
        //println(s"vid: $vid community: $community $q = ($k_i_in / $M) -  ( ($sigmaTot * $k_i) / math.pow($M, 2) )")
        if (q < 0) 0 else q
    })  
    
    // Bu noktada, myRDD boş olabilir mi?
    if (newVerts.isEmpty()) {
      // Handle empty RDD case
      print("rdd boş\n")
    } else {
    }
    
    // return the modularity value of the graph along with the 
    // graph. vertices are labeled with their community
    
    val actualQ = newVerts.values.reduce(_+_)
    return (actualQ,louvainBasedGraphCached,count/2)
  }

  def louvainVertJoin(louvainGraph: Graph[LouvainVertex, Double], communityRDD: VertexRDD[Map[(Long, Double), Double]], m: Broadcast[Double], even: Boolean) = {

    // innerJoin[U, VD2](other: RDD[(VertexId, U)])(f: (VertexId, VD, U) => VD2): VertexRDD[VD2]
    louvainGraph.vertices.innerJoin(communityRDD)((vid, louvainVertex, communityMessages) => {

      var bestCommunity = louvainVertex.communityType
      val startingCommunityId = bestCommunity
      var maxDeltaQ = BigDecimal(0.0);
      var bestSigmaTot = 0d

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
        louvainVertex.communityTotalDegree = bestSigmaTot
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
         testSigmaTot: Double,
         edgeWeightInCommunity: Double,
         nodeWeight: Double,
         internalWeight: Double,
         totalEdgeWeight: Double): BigDecimal = {

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

  
  private def sendCommunityInfo(edgeTrip: EdgeContext[LouvainVertex, Double, Map[(Long, Double), Double]]) = {
    /*val m1 = (edgeTrip.dstId,Map((edgeTrip.srcAttr.communityType,edgeTrip.srcAttr.communityTotalDegree)->edgeTrip.attr))
	  val m2 = (edgeTrip.srcId,Map((edgeTrip.dstAttr.communityType,edgeTrip.dstAttr.communityTotalDegree)->edgeTrip.attr))
	  Iterator(m1, m2)    */
    edgeTrip.sendToDst(Map((edgeTrip.srcAttr.communityType,edgeTrip.srcAttr.communityTotalDegree)->edgeTrip.attr))
    edgeTrip.sendToSrc(Map((edgeTrip.dstAttr.communityType,edgeTrip.dstAttr.communityTotalDegree)->edgeTrip.attr))
  }
  
  
  
  /**
   *  Merge neighborhood community data into a single message for each vertex
   */
  private def mergeCommunityInfo(m1:Map[(Long,Double),Double],m2:Map[(Long,Double),Double]) ={
    val newMap = scala.collection.mutable.HashMap[(Long,Double),Double]()
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
  /*private def mergeCommunityInfo(m1:Map[(Long,Long),Long],m2:Map[(Long,Long),Long]) ={
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
  }*/

  /**
   * Compress a graph by its communities, aggregate both internal node weights and edge
   * weights within communities.
   */
  def compressGraph(graph:Graph[LouvainVertex,Double],debug:Boolean=true) : Graph[LouvainVertex,Double] = {

    // aggregate the edge weights of self loops. edges with both src and dst in the same community.
	// WARNING  can not use graph.mapReduceTriplets because we are mapping to new vertexIds
    val internalEdgeWeights = graph.triplets.flatMap(et=>{
    	if (et.srcAttr.communityType == et.dstAttr.communityType){
            Iterator( ( et.srcAttr.communityType, 2*et.attr) )  // count the weight from both nodes  // count the weight from both nodes
          } 
          else Iterator.empty  
    }).reduceByKey(_+_)
    
     
    // aggregate the internal weights of all nodes in each community
    var internalWeights = graph.vertices.values.map(vdata=> (vdata.communityType,vdata.communityInDegree)).reduceByKey(_+_)
   
    // join internal weights and self edges to find new interal weight of each community
    val newVerts = internalWeights.leftOuterJoin(internalEdgeWeights).map({case (vid,(weight1,weight2Option)) =>
      val weight2 = weight2Option.getOrElse(0D)
      val state = new LouvainVertex()
      state.communityType = vid
      state.changed = false
      state.communityTotalDegree = 0L
      state.communityInDegree = weight1+weight2
      state.degreeOfNode = 0L
      (vid,state)
    }).cache()
    
    
    // translate each vertex edge to a community edge
    val edges = graph.triplets.flatMap(et=> {
       val src = math.min(et.srcAttr.communityType,et.dstAttr.communityType)
       val dst = math.max(et.srcAttr.communityType,et.dstAttr.communityType)
       if (src != dst) Iterator(new Edge(src, dst, et.attr))
       else Iterator.empty
    }).cache()
    
    
    // generate a new graph where each community of the previous
    // graph is now represented as a single vertex
    val compressedGraph = Graph(newVerts,edges)
      .partitionBy(PartitionStrategy.EdgePartition2D).groupEdges(_+_)
    
    // calculate the weighted degree of each node
    /*val nodeWeightMapFunc = (e:EdgeTriplet[LouvainVertex,Long]) => Iterator((e.srcId,e.attr), (e.dstId,e.attr))
    val nodeWeightReduceFunc = (e1:Long,e2:Long) => e1+e2*/
    /*val nodeWeightMapFunc = (ctx: EdgeContext[LouvainVertex, Long, Long]) => {
      ctx.sendToSrc(ctx.attr)
      ctx.sendToDst(ctx.attr)
    }

    val nodeWeightReduceFunc = (e1: Long, e2: Long) => e1 + e2

    val nodeWeights = compressedGraph.aggregateMessages(nodeWeightMapFunc,nodeWeightReduceFunc)*/
    val nodeWeightMapFunc = (e:EdgeTriplet[LouvainVertex,Double]) => Iterator((e.srcId,e.attr), (e.dstId,e.attr))
    val nodeWeightReduceFunc = (e1:Double,e2:Double) => e1+e2
    val nodeWeights:VertexRDD[Double] = graph.aggregateMessages(
      trip => {
        trip.sendToSrc(trip.attr)
        trip.sendToDst(trip.attr)
      },
      (a,b) => a + b
    )
    
    // fill in the weighted degree of each node
   // val louvainGraph = compressedGraph.joinVertices(nodeWeights)((vid,data,weight)=> { 
   val louvainGraph = compressedGraph.outerJoinVertices(nodeWeights)((vid,data,weightOption)=> { 
      val weight = weightOption.getOrElse(0D)
      data.communityTotalDegree = weight +data.communityInDegree
      data.degreeOfNode = weight
      data
    }).cache()
    louvainGraph.vertices.count()
    louvainGraph.triplets.count() // materialize the graph
    
    newVerts.unpersist(blocking=false)
    edges.unpersist(blocking=false)
    return louvainGraph
    
   
    
  }
  // debug printing
   private def printlouvain(graph:Graph[LouvainVertex,Long]) = {
     print("\ncommunity label snapshot\n(vid,community,sigmaTot)\n")
     graph.vertices.mapValues((vid,vdata)=> (vdata.communityType,vdata.communityTotalDegree)).collect().foreach(f=>println(" "+f))
   }
  
 
   
   // debug printing
   private def printedgetriplets(graph:Graph[LouvainVertex,Long]) = {
     print("\ncommunity label snapshot FROM TRIPLETS\n(vid,community,sigmaTot)\n")
     (graph.triplets.flatMap(e=> Iterator((e.srcId,e.srcAttr.communityType,e.srcAttr.communityTotalDegree), (e.dstId,e.dstAttr.communityType,e.dstAttr.communityTotalDegree))).collect()).foreach(f=>println(" "+f))
   }

}
