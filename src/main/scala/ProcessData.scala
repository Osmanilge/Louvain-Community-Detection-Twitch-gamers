import scala.io.Source
object ProcessData {
    def loadAsTable(filePath: String ): List[(Int, Int, Double, Double, Double)] = {
        // Dosya yolu

        // Dosyayı aç ve satırları oku
        val source = Source.fromFile(filePath)
        val rows = source.getLines().toList
        source.close()

        // Satırları işle
        val pattern = """\((\d+),\{Type of Community:(\d+),TotalDegreeOfCommunity:([\d.]+),inDegreeOfCommunity:([\d.]+),degreeOfNode:([\d.]+)\}\)""".r
        val table = rows.flatMap {
            case pattern(id, community, totalDegree, inDegree, nodeDegre) =>
            Some((id.toInt, community.toInt, totalDegree.toDouble, inDegree.toDouble, nodeDegre.toDouble))
            case _ => None
        }
        table
    }
    def readIdAndLanguageFromFile(filePath: String): List[(Int, String)] = {
    val source = Source.fromFile(filePath)
    val lines = source.getLines().toList.tail  // Skip the first line as it is the header
    source.close()

    // Assign incremental ids starting from 0
    val table = lines.zipWithIndex.map { case (line, index) =>
      val fields = line.split(",")
      (index, fields(7))
    }

    table
  }
    def main(args: Array[String]) {
    

    // Oluşturulan tabloyu ekrana yazdır
    val level0tablo1 = loadAsTable("src/level_0_vertices/part-00000").map { case (id, community, _, _, _) => (id, community) }     
    
    val level0tablo2 = loadAsTable("src/level_0_vertices/part-00001").map { case (id, community, _, _, _) => (id, community) }     

        // İki tabloyu birleştir
    val birlesikTablo = level0tablo1 ++ level0tablo2

    val level1tablo1 = loadAsTable("src/level_1_vertices/part-00000").map { case (id, community, _, _, _) => (id, community) }     
    
    val level1tablo2 = loadAsTable("src/level_1_vertices/part-00001").map { case (id, community, _, _, _) => (id, community) }     

        // İki tabloyu birleştir
    val birlesikTablo2 = level1tablo1 ++ level1tablo2

    val lastTable = birlesikTablo.flatMap { case (id1, community1) =>
    birlesikTablo2.filter { case (id2, community2) =>
      id2 == community1
    }.map { case (id2, community2) =>
      (community2,id1, community1)
    }
  }

    // Birleştirilmiş tabloyu ekrana yazdır
    //lastTable.foreach(println)
    val finalLastTable = lastTable.map{ case(community2,id1, community1) =>
      (community2,id1)}


    finalLastTable.foreach(println)


    val users = readIdAndLanguageFromFile("src/sampleFeatures.csv")
    

    val data = users.flatMap { case (id1, language) =>
    finalLastTable.filter { case (community2, id2) =>
      id2 == id1
    }.map { case (community2, id2) =>
      (community2,language)
    }
    }

    
    val numOfLanguages = data.groupBy{case (community2,language) => (community2,language)}
    .mapValues(_.size).map{case ((community2,language),size) => (community2,language,size)}
    print("\n\n")

    //numOfLanguages.foreach(println)

    val totalSizeOfCommunity = data.groupBy{case (community2,_) => (community2)}.mapValues(_.size)
    
    val ratioOfLanguages = totalSizeOfCommunity.map { case (id1, total) =>
    numOfLanguages.filter { case (community2, language, size) =>
      community2 == id1
    }.map { case (community2, language, size) =>
        (community2,language, size*100/total)
    }
    }

    ratioOfLanguages.foreach(println)

}      
}
