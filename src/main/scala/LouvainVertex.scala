class LouvainVertex extends Serializable{

  var communityType = -1L
  var communityTotalDegree = 0L
  var communityInDegree = 0L  // self edges
  var degreeOfNode = 0L;  //out degree
  var changed = false
   
  override def toString(): String = {
    "{Type of Community:"+communityType+",TotalDegreeOfCommunity:"+communityTotalDegree+
    ",inDegreeOfCommunity:"+communityInDegree+",degreeOfNode:"+degreeOfNode+"}"
  }

}
