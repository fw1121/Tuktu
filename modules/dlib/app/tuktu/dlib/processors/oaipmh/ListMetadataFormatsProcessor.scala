package tuktu.dlib.processors.oaipmh

import play.api.libs.iteratee.Enumeratee
import play.api.libs.json._
import play.api.libs.functional.syntax._
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.xml._
import tuktu.api._
import tuktu.api.BaseProcessor
import tuktu.dlib.utils.oaipmh

/**
 * Retrieves the metadata formats available from a repository. An optional argument restricts the request to the formats available for a specific item.
 */
class ListMetadataFormatsProcessor(resultName: String) extends BaseProcessor(resultName) 
{
   var target: String = _
   var identifier: Option[String] = _
   var toj: Boolean = _
    
    override def initialize(config: JsObject) 
    {
        target = (config \ "target").as[String]
        identifier = (config \ "identifier").asOpt[String]
        toj = (config \ "toJSON").asOpt[Boolean].getOrElse(false)
    }

    override def processor(): Enumeratee[DataPacket, DataPacket] = Enumeratee.mapM((data: DataPacket) => Future {
      new DataPacket(for (datum <- data.data) yield {
        val trgt = utils.evaluateTuktuString( target , datum )
        val id = identifier match
        {
          case None => ""
          case Some( i ) => "&identifier=" + utils.evaluateTuktuString( i , datum )
        }
        val verb = trgt + "?verb=ListMetadataFormats" + id
        toj match
        {
          case false => datum + ( resultName -> getFormats( verb ) )
          case true => datum + ( resultName ->  getFormats( verb ).map{ f => oaipmh.xml2jsObject( f ) }  )
        }        
      })
    })
  /**
   * Utility method to retrieve the metadata formats supported by a repository
   * @param verb: The OAI-PMH ListFormats request
   * @return the metadata formats supported by the repository
   */
    def getFormats( verb: String ): Seq[String] =
    {
      val response = oaipmh.harvest( verb )
      // check for error
      (response \\ "error").headOption match
      {
        case None => {
          val f = ((response \ "ListMetadataFormats" \ "metadataFormat" ).toSeq map { frmt => frmt.toString.trim })
          for (format <- f; if (!format.isEmpty)) yield( format )
        }  
        case Some( err ) => Seq(response.toString)
      }
    }
}