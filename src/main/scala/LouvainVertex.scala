class LouvainVertex extends Serializable{

  var communityType = -1L
  var communityTotalDegree = 0D
  var communityInDegree = 0D  // self edges
  var degreeOfNode = 0D;  //out degree
  var changed = false
   
  override def toString(): String = {
    "{Type of Community:"+communityType+",TotalDegreeOfCommunity:"+communityTotalDegree+
    ",inDegreeOfCommunity:"+communityInDegree+",degreeOfNode:"+degreeOfNode+"}"
  }

}
