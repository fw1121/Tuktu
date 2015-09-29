package tuktu.api

import java.util.Date
import java.util.regex.Pattern
import scala.util.hashing.MurmurHash3
import org.joda.time.DateTime
import play.api.Play.current
import play.api.cache.Cache
import play.api.libs.json._
import play.api.libs.iteratee.Enumeratee
import play.api.libs.concurrent.Akka
import play.api.Logger
import scala.concurrent.ExecutionContext.Implicits.global

object utils {
    val pattern = Pattern.compile("\\$\\{(.*?)\\}")

    /**
     * Enumeratee for error-logging and handling
     * @param idString A string used to identify the flow this logEnumeratee is part of. A mapping exists
     * within the monitor that maps this ID to a generator name.
     */
    def logEnumeratee[T](idString: String) = Enumeratee.recover[T] {
        case (e, input) => {
            // Notify the monitor so it can kill our flow
            Akka.system.actorSelection("user/TuktuMonitor") ! new ErrorNotificationPacket(idString)

            // Log the error
            Logger.error("Error happened on: " + input, e)
        }
    }

    /**
     * Checks if a string contains variables that can be populated using evaluateTuktuString
     */
    def containsTuktuStringVariable(str: String) = {
        val matcher = pattern.matcher(str)
        matcher.find()
    }

    /**
     * Evaluates a Tuktu string to resolve variables in the actual string
     */
    def evaluateTuktuString(str: String, vars: Map[String, Any]) = {
        if(vars.isEmpty) {
            str
        }
        else {
            // determine max length for performance reasons
            val maxKeyLength = vars.maxBy(kv => kv._1.length)._1.length
            val result = new StringBuilder
            // a temporary buffer to determine if we need to replace this
            val buffer = new StringBuilder
            // The prefix length of TuktuStrings "${".length = 2
            val prefixSize = "${".length
            str.foreach { currentChar =>
                if (buffer.length == 0) {
                    if (currentChar.equals('$')) {
                        buffer.append(currentChar)
                    } else {
                        result.append(currentChar)
                    }
                } else if (buffer.length == 1) {
                    buffer.append(currentChar)
                    if (!currentChar.equals('{')) {
                        result.append(buffer)
                        buffer.setLength(0)
                    }
                } else if (buffer.length > maxKeyLength + prefixSize) {
                    result.append(buffer).append(currentChar)
                    buffer.setLength(0)
                } else {
                    if (currentChar.equals('}')) {
                        // apply with variable in vars, or leave it be if it cannot be found
                        result.append(vars.getOrElse(buffer.substring(prefixSize), buffer + "}").toString)
                        buffer.setLength(0)
                    } else {
                        buffer.append(currentChar)
                    }
                }
            }
            // add any left overs
            result.append(buffer)
            result.toString
        }
    }

    /**
     * Evaluates a Tuktu config to resolve variables in it
     */
    def evaluateTuktuConfig(str: String, vars: Map[String, Any]): String = {
        if(vars.isEmpty) {
            str
        } else {
            // determine max length for performance reasons
            val maxKeyLength = vars.maxBy(kv => kv._1.length)._1.length
            val result = new StringBuilder
            // a temporary buffer to determine if we need to replace this
            val buffer = new StringBuilder
            // The prefix length of TuktuStrings "#{".length = 2
            val prefixSize = "#{".length
            str.foreach { currentChar =>
                if (buffer.length == 0) {
                    if (currentChar.equals('#')) {
                        buffer.append(currentChar)
                    } else {
                        result.append(currentChar)
                    }
                } else if (buffer.length == 1) {
                    buffer.append(currentChar)
                    if (!currentChar.equals('{')) {
                        result.append(buffer)
                        buffer.setLength(0)
                    }
                } else if (buffer.length > maxKeyLength + prefixSize) {
                    result.append(buffer).append(currentChar)
                    buffer.setLength(0)
                } else {
                    if (currentChar.equals('}')) {
                        // apply with variable in vars, or leave it be if it cannot be found
                        result.append(vars.getOrElse(buffer.substring(prefixSize), buffer + "}").toString)
                        buffer.setLength(0)
                    } else {
                        buffer.append(currentChar)
                    }
                }
            }
            // add any left overs
            result.append(buffer)
            result.toString
        }
    }

    /**
     * Recursively evaluates a Tuktu config to resolve variables in it
     * Overloaded to get the correct return type for every possible use case
     */
    def evaluateTuktuConfig(json: JsValue, vars: Map[String, Any]): JsValue = json match {
        case obj: JsObject  => evaluateTuktuConfig(obj, vars)
        case arr: JsArray   => evaluateTuktuConfig(arr, vars)
        case str: JsString  => evaluateTuktuConfig(str, vars)
        case value: JsValue => value // Nothing to do for any other JsTypes
    }

    def evaluateTuktuConfig(obj: JsObject, vars: Map[String, Any]): JsObject = {
        new JsObject(obj.value.mapValues(value => evaluateTuktuConfig(value, vars)).toSeq)
    }

    def evaluateTuktuConfig(arr: JsArray, vars: Map[String, Any]): JsArray = {
        new JsArray(arr.value.map(value => evaluateTuktuConfig(value, vars)))
    }

    def evaluateTuktuConfig(str: JsString, vars: Map[String, Any]): JsString = {
        new JsString(evaluateTuktuConfig(str.value, vars))
    }

    /**
     * Recursively traverses a path of keys until it finds a value (or fails to traverse,
     * in which case a default value is used)
     */
    def fieldParser(input: Map[String, Any], path: List[String], defaultValue: Option[JsValue]): Any = path match {
        case Nil => input
        case someKey :: Nil => {
            if (input.contains(someKey))
                input(someKey)
            else
                defaultValue.getOrElse(null)
        }
        case someKey :: trailPath => {
            // Get the remainder
            if (input.contains(someKey)) {
                // See if we can cast it
                try {
                    if (input(someKey).isInstanceOf[JsValue])
                        jsonParser(input(someKey).asInstanceOf[JsValue], trailPath, defaultValue)
                    else
                        fieldParser(input(someKey).asInstanceOf[Map[String, Any]], trailPath, defaultValue)
                } catch {
                    case e: ClassCastException => defaultValue.getOrElse(null)
                }
            } else {
                // Couldn't find it
                defaultValue.getOrElse(null)
            }
        }
    }

    /**
     * Recursively traverses a JSON object of keys until it finds a value (or fails to traverse,
     * in which case a default value is used)
     */
    def jsonParser(json: JsValue, jsPath: List[String], defaultValue: Option[JsValue]): JsValue = jsPath match {
        case List() => json
        case js :: trailPath => {
            // Get the remaining value from the json
            val newJson = (json \ js).asOpt[JsValue]
            newJson match {
                case Some(nj) => {
                    // Recurse into new JSON
                    jsonParser(nj, trailPath, defaultValue)
                }
                case None => {
                    // Couldn't find it, return the best we can
                    defaultValue match {
                        case Some(value) => value
                        case None        => json
                    }
                }
            }
        }
    }

    /**
     * Turns a map of string -> any into a JSON object
     */
    def anyMapToJson(map: Map[String, Any], mongo: Boolean = false): JsObject = {
        /**
         * Dealing with objects
         */
        def mapToJsonHelper(mapping: List[(Any, Any)]): JsObject = mapping match {
            case Nil => Json.obj()
            case head :: remainder => Json.obj(head._1.toString -> (head._2 match {
                case a: String     => a
                case a: Char       => a.toString
                case a: Int        => a
                case a: Double     => a
                case a: Long       => a
                case a: Short      => a
                case a: Float      => a
                case a: Boolean    => a
                case a: Date       => if (mongo) Json.obj("$date" -> a.getTime) else a
                case a: DateTime   => if (mongo) Json.obj("$date" -> a.getMillis) else a
                case a: JsValue    => a
                case a: BigDecimal => a
                case a: Seq[Any]   => anyListToJsonHelper(a)
                case a: Map[_, _]  => mapToJsonHelper(a.toList)
                case _             => head._2.toString
            })) ++ mapToJsonHelper(remainder)
        }

        /**
         * Dealing with arrays
         */
        def anyListToJsonHelper(list: Seq[Any], mongo: Boolean = false): JsArray = list.toList match {
            case Nil => Json.arr()
            case elem :: remainder => Json.arr(elem match {
                case a: String     => a
                case a: Char       => a.toString
                case a: Int        => a
                case a: Double     => a
                case a: Long       => a
                case a: Short      => a
                case a: Float      => a
                case a: Boolean    => a
                case a: Date       => if (mongo) Json.obj("$date" -> a.getTime) else a
                case a: DateTime   => if (mongo) Json.obj("$date" -> a.getMillis) else a
                case a: JsValue    => a
                case a: BigDecimal => a
                case a: Seq[Any]   => anyListToJsonHelper(a)
                case a: Map[_, _]  => mapToJsonHelper(a.toList)
                case _             => elem.toString
            }) ++ anyListToJsonHelper(remainder)
        }

        mapToJsonHelper(map.toList)
    }

    /**
     * ---------------------
     * JSON helper functions
     * ---------------------
     */

    /**
     * Takes a JsValue and returns a scala object
     */
    def JsValueToAny(json: JsValue): Any = json match {
        case a: JsString  => a.value
        case a: JsNumber  => a.value
        case a: JsBoolean => a.value
        case a: JsObject  => JsObjectToMap(a)
        case a: JsArray   => JsArrayToSeqAny(a)
        case a            => a.toString
    }

    /**
     * Converts a JsArray to a Seq[Any]
     */
    def JsArrayToSeqAny(arr: JsArray): Seq[Any] =
        for (field <- arr.value) yield JsValueToAny(field)

    /**
     * Converts a JsObject to Map[String, Any]
     */
    def JsObjectToMap(json: JsObject): Map[String, Any] =
        json.value.mapValues(jsValue => JsValueToAny(jsValue)).toMap

    def indexToNodeHasher(keys: List[Any], replicationCount: Option[Int], includeSelf: Boolean): List[String] =
        indexToNodeHasher(keys.map(_.toString).mkString(""), replicationCount, includeSelf)
    /**
     * Hashes an index to a (number of) node(s)
     */
    def indexToNodeHasher(keyString: String, replicationCount: Option[Int], includeSelf: Boolean): List[String] = {
        def indexToNodeHasherHelper(nodes: List[String], replCount: Int): List[String] = {
            // Get a node
            val node = nodes(Math.abs(MurmurHash3.stringHash(keyString) % nodes.size))

            // Return more if required
            node :: {
                if (replCount > 1) {
                    indexToNodeHasherHelper(nodes diff List(node), replCount - 1)
                } else List()
            }
        }

        val clusterNodes = {
            if (includeSelf)
                Cache.getOrElse[scala.collection.mutable.Map[String, ClusterNode]]("clusterNodes")(scala.collection.mutable.Map()).keys.toList
            else
                Cache.getOrElse[scala.collection.mutable.Map[String, ClusterNode]]("clusterNodes")(scala.collection.mutable.Map()).keys.toList diff
                    List(Cache.getAs[String]("homeAddress").getOrElse("127.0.0.1"))
        }
        replicationCount match {
            case Some(cnt) => indexToNodeHasherHelper(clusterNodes, cnt - {
                if (includeSelf) 1 else 0
            })
            case None => clusterNodes
        }
    }
}