import org.apache.spark.graphx._
import org.apache.spark.graphx.Graph.graphToGraphOps
import org.apache.spark.rdd.RDD
import scala.reflect.ClassTag

object Louvain {
  
  def generateLouvainGraph[VD: ClassTag](graph: Graph[VD,Long]): Graph[LouvainVertex, Long] = {
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
}
