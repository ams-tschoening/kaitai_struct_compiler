package io.kaitai.struct.format

import java.nio.charset.Charset
import java.util
import java.util.{List => JList, Map => JMap}

import com.fasterxml.jackson.annotation.{JsonCreator, JsonProperty}
import io.kaitai.struct.Utils
import io.kaitai.struct.exprlang.{Ast, DataType, Expressions}
import io.kaitai.struct.exprlang.DataType._

import scala.collection.JavaConversions._

sealed trait RepeatSpec
case class RepeatExpr(expr: Ast.expr) extends RepeatSpec
case class RepeatUntil(expr: Ast.expr) extends RepeatSpec
case object RepeatEos extends RepeatSpec
case object NoRepeat extends RepeatSpec

case class ConditionalSpec(ifExpr: Option[Ast.expr], repeat: RepeatSpec)

trait AttrLikeSpec {
  def dataType: BaseType
  def cond: ConditionalSpec
  def doc: Option[String]

  def isArray: Boolean = cond.repeat != NoRepeat

  def dataTypeComposite: BaseType = {
    if (isArray) {
      ArrayType(dataType)
    } else {
      dataType
    }
  }
}

case class AttrSpec(
  id: Identifier,
  dataType: BaseType,
  cond: ConditionalSpec = ConditionalSpec(None, NoRepeat),
  doc: Option[String] = None
) extends AttrLikeSpec

object AttrSpec {
  val LEGAL_KEYS = Set(
    "id",
    "doc",
    "type",
    "process",
    "contents",
    "size",
    "size-eos",
    "if",
    "encoding",
    "repeat",
    "repeat-expr",
    "repeat-until"
  )

  val LEGAL_KEYS_BYTES = Set(
    "size",
    "size-eos",
    "process"
  )

  val LEGAL_KEYS_STR = Set(
    "size",
    "size-eos",
    "encoding"
  )

  val LEGAL_KEYS_STRZ = Set(
    "encoding",
    "terminator",
    "consume",
    "include",
    "eos-error"
  )

  val LEGAL_KEYS_ENUM = Set(
    "enum"
  )

  def fromYaml(src: Any, path: List[String]): AttrSpec = {
    val srcMap = ParseUtils.asMapStr(src, path)

    val idStr = ParseUtils.getValueStr(srcMap, "id", path)
    val id = Some(NamedIdentifier(idStr))

    val doc = ParseUtils.getOptValueStr(srcMap, "doc", path)
    val process = ProcessExpr.fromStr(ParseUtils.getOptValueStr(srcMap, "process", path)) // TODO: add proper path propagation
    val contents: Option[Array[Byte]] = None // FIXME: proper contents parsing
    val size = ParseUtils.getOptValueStr(srcMap, "size", path).map(Expressions.parse)
    val sizeEos = ParseUtils.getOptValueBool(srcMap, "size-eos", path).getOrElse(false)
    val ifExpr = ParseUtils.getOptValueStr(srcMap, "if", path).map(Expressions.parse)
    val encoding = ParseUtils.getOptValueStr(srcMap, "encoding", path)
    val repeat = ParseUtils.getOptValueStr(srcMap, "repeat", path)
    val repeatExpr = ParseUtils.getOptValueStr(srcMap, "repeat-expr", path).map(Expressions.parse)
    val repeatUntil = ParseUtils.getOptValueStr(srcMap, "repeat-until", path).map(Expressions.parse)
    val terminator = ParseUtils.getOptValueInt(srcMap, "terminator", path).getOrElse(0)
    val consume = ParseUtils.getOptValueBool(srcMap, "consume", path).getOrElse(true)
    val include = ParseUtils.getOptValueBool(srcMap, "include", path).getOrElse(false)
    val eosError = ParseUtils.getOptValueBool(srcMap, "eos-error", path).getOrElse(true)
    val enum = ParseUtils.getOptValueStr(srcMap, "enum", path)

    val typObj = srcMap.get("type")
    val dataType: BaseType = typObj match {
      case simpleType: Option[String] =>
        DataType.fromYaml(
          simpleType, MetaSpec.globalMeta.get.endian,
          size, sizeEos,
          encoding, terminator, include, consume, eosError,
          contents, enum, process
        )
      case Some(switchMap: Map[String, Any]) =>
        parseSwitch(
          switchMap,
          size, sizeEos,
          encoding, terminator, include, consume, eosError,
          contents, enum, process
        )
      case unknown =>
        throw new YAMLParseException(s"expected map or string, found $unknown", path ++ List("type"))
    }

    val legalKeys = LEGAL_KEYS ++ (dataType match {
      case _: BytesType => LEGAL_KEYS_BYTES
      case _: StrEosType | _: StrByteLimitType => LEGAL_KEYS_STR
      case _: StrZType => LEGAL_KEYS_STRZ
      case _: UserType => LEGAL_KEYS_BYTES
      case EnumType(_, _) => LEGAL_KEYS_ENUM
      case SwitchType(on, cases) => LEGAL_KEYS_BYTES
      case _ => Set()
    })

    ParseUtils.ensureLegalKeys(srcMap, legalKeys, path)

    val repeatSpec = repeat match {
      case Some("until") => RepeatUntil(repeatUntil.get)
      case Some("expr") => RepeatExpr(repeatExpr.get)
      case Some("eos") => RepeatEos
      case None => NoRepeat
    }

    AttrSpec(id.get, dataType, ConditionalSpec(ifExpr, repeatSpec), doc)
  }

  def parseContentSpec(c: Object): Array[Byte] = {
    c match {
      case s: String =>
        s.getBytes(Charset.forName("UTF-8"))
      case objects: util.ArrayList[_] =>
        val bb = new scala.collection.mutable.ArrayBuffer[Byte]
        objects.foreach {
          case s: String =>
            bb.appendAll(Utils.strToBytes(s))
          case integer: Integer =>
            bb.append(Utils.clampIntToByte(integer))
          case el =>
            throw new RuntimeException(s"Unable to parse fixed content in array: $el")
        }
        bb.toArray
      case _ =>
        throw new RuntimeException(s"Unable to parse fixed content: ${c.getClass}")
    }
  }

  private def parseSwitch(
    switchSpec: Map[String, Any],
    size: Option[Ast.expr],
    sizeEos: Boolean,
    encoding: Option[String],
    terminator: Int,
    include: Boolean,
    consume: Boolean,
    eosError: Boolean,
    contents: Option[Array[Byte]],
    enumRef: Option[String],
    process: Option[ProcessExpr]
  ): BaseType = {
    val _on = switchSpec("switch-on").asInstanceOf[String]
    val _cases: Map[String, String] = switchSpec.get("cases") match {
      case None => Map()
      case Some(x) => x.asInstanceOf[JMap[String, String]].toMap
    }

    val on = Expressions.parse(_on)
    val cases = _cases.map { case (condition, typeName) =>
      Expressions.parse(condition) -> DataType.fromYaml(
        Some(typeName), MetaSpec.globalMeta.get.endian,
        size, sizeEos,
        encoding, terminator, include, consume, eosError,
        contents, enumRef, process
      )
    }

    // If we have size defined, and we don't have any "else" case already, add
    // an implicit "else" case that will at least catch everything else as
    // "untyped" byte array of given size
    val addCases: Map[Ast.expr, BaseType] = if (cases.containsKey(SwitchType.ELSE_CONST)) {
      Map()
    } else {
      (size, sizeEos) match {
        case (Some(sizeValue), false) =>
          Map(SwitchType.ELSE_CONST -> BytesLimitType(sizeValue, process))
        case (None, true) =>
          Map(SwitchType.ELSE_CONST -> BytesEosType(process))
        case (None, false) =>
          Map()
        case (Some(_), true) =>
          throw new RuntimeException("can't have both `size` and `size-eos` defined")
      }
    }

    SwitchType(on, cases ++ addCases)
  }

  private def boolFromStr(s: String, byDef: Boolean): Boolean = {
    s match {
      case "true" | "yes" | "1" => true
      case "false" | "no" | "0" | "" => false
      case null => byDef
    }
  }
}
