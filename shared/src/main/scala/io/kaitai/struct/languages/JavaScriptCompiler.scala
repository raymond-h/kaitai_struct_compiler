package io.kaitai.struct.languages

import io.kaitai.struct.datatype.DataType._
import io.kaitai.struct.datatype.{DataType, FixedEndian, InheritedEndian}
import io.kaitai.struct.exprlang.Ast
import io.kaitai.struct.exprlang.Ast.expr
import io.kaitai.struct.format._
import io.kaitai.struct.languages.components._
import io.kaitai.struct.translators.JavaScriptTranslator
import io.kaitai.struct.{ClassTypeProvider, RuntimeConfig, Utils}

class JavaScriptCompiler(typeProvider: ClassTypeProvider, config: RuntimeConfig)
  extends LanguageCompiler(typeProvider, config)
    with ObjectOrientedLanguage
    with UpperCamelCaseClasses
    with SingleOutputFile
    with UniversalDoc
    with AllocateIOLocalVar
    with EveryReadIsExpression
    with FixedContentsUsingArrayByteLiteral {
  import JavaScriptCompiler._

  override val translator = new JavaScriptTranslator(typeProvider)

  override def indent: String = "  "
  override def outFileName(topClassName: String): String = s"${type2class(topClassName)}.js"

  override def fileHeader(name: String): Unit = {
    out.puts(s"// $headerComment")

    out.puts

    out.puts("(function(global, factory) {")
    out.inc
    out.puts("// Export for amd environments")
    out.puts("var isAmd = (typeof define === 'function' && define.amd);")
    out.puts("if (isAmd) {")
    out.inc
    out.puts(s"define('${type2class(name)}', ['KaitaiStream'], factory);") // TODO: Fix dep name
    out.dec
    out.puts("}")

    out.puts

    out.puts("// Export for CommonJS")
    out.puts("var isCommonJs = (typeof module === 'object' && module && module.exports);")
    out.puts("if (isCommonJs) {")
    out.inc
    out.puts(s"module.exports = factory(require('kaitai-runtime'));") // TODO: Fix dep name
    out.dec
    out.puts("}")

    out.puts

    out.puts("// Export on global scope (used in browsers)")
    out.puts("// only if neither neither AMD nor CommonJS were detected")
    out.puts("if (!isAmd && !isCommonJs) {")
    out.inc
    out.puts(s"global.${type2class(name)} = factory(global.KaitaiStream);")
    out.dec
    out.puts("}")
    out.dec
    out.puts(s"})(this, function($kstreamName) {")
    out.inc
  }

  override def fileFooter(name: String): Unit = {
    out.dec
    out.puts("});")
  }

  override def opaqueClassDeclaration(classSpec: ClassSpec): Unit = {
    val typeName = classSpec.name.head
    out.puts
    out.puts("if (typeof require === 'function')")
    out.inc
    out.puts(s"var ${type2class(typeName)} = require('./${outFileName(typeName)}');")
    out.dec
  }

  override def classHeader(name: List[String]): Unit = {
    val shortClassName = type2class(name.last)

    val addNameExpr = if (name.size > 1) {
      s" = ${types2class(name.takeRight(2))}"
    } else {
      ""
    }

    if (name.size > 1) {
      out.puts
      out.puts(s"var $shortClassName$addNameExpr = (function() {")
      out.inc
    }
  }

  override def classFooter(name: List[String]): Unit = {
    out.puts
    out.puts(s"return ${type2class(name.last)};")

    if (name.size > 1) {
      out.dec
      out.puts("})();")
    }
  }

  override def classConstructorHeader(name: List[String], parentClassName: DataType, rootClassName: List[String], isHybrid: Boolean): Unit = {
    val endianSuffix = if (isHybrid) {
      ", _is_le"
    } else {
      ""
    }

    out.puts(s"function ${type2class(name.last)}(_io, _parent, _root$endianSuffix) {")
    out.inc
    out.puts("this._io = _io;")
    out.puts("this._parent = _parent;")
    out.puts("this._root = _root || this;")

    if (isHybrid)
      out.puts("this._is_le = _is_le;")

    if (debug) {
      out.puts("this._debug = {};")
      out.dec
      out.puts("}")
      out.puts
      out.puts(s"${type2class(name.last)}.prototype._read = function() {")
      out.inc
    } else {
      out.puts
    }
  }

  override def classConstructorFooter: Unit = {
    out.dec
    out.puts("}")
  }

  override def runRead(): Unit = {
    out.puts("this._read();")
  }

  override def runReadCalc(): Unit = {
    out.puts
    out.puts(s"if (this._is_le === true) {")
    out.inc
    out.puts("this._readLE();")
    out.dec
    out.puts("} else if (this._is_le === false) {")
    out.inc
    out.puts("this._readBE();")
    out.dec
    out.puts("} else {")
    out.inc
    out.puts("throw new KaitaiUndecidedEndiannessError();")
    out.dec
    out.puts("}")
  }

  override def readHeader(endian: Option[FixedEndian], isEmpty: Boolean) = {
    val suffix = endian match {
      case Some(e) => e.toSuffix.toUpperCase
      case None => ""
    }
    out.puts(s"${type2class(typeProvider.nowClass.name.last)}.prototype._read$suffix = function() {")
    out.inc
  }

  override def readFooter() = {
    out.dec
    out.puts("}")
  }

  override def attributeDeclaration(attrName: Identifier, attrType: DataType, condSpec: ConditionalSpec): Unit = {}

  override def attributeReader(attrName: Identifier, attrType: DataType, condSpec: ConditionalSpec): Unit = {}

  override def universalDoc(doc: DocSpec): Unit = {
    // JSDoc docstring style: http://usejsdoc.org/about-getting-started.html
    out.puts
    out.puts( "/**")

    doc.summary.foreach((summary) => out.putsLines(" * ", summary))

    // http://usejsdoc.org/tags-see.html
    doc.ref match {
      case TextRef(text) =>
        out.putsLines(" * ", s"@see $text")
      case UrlRef(url, text) =>
        out.putsLines(" * ", s"@see {@link $url|$text}")
      case NoRef =>
    }

    out.puts( " */")
  }

  override def attrParseHybrid(leProc: () => Unit, beProc: () => Unit): Unit = {
    out.puts("if (this._is_le) {")
    out.inc
    leProc()
    out.dec
    out.puts("} else {")
    out.inc
    beProc()
    out.dec
    out.puts("}")
  }

  override def attrFixedContentsParse(attrName: Identifier, contents: String): Unit = {
    out.puts(s"${privateMemberName(attrName)} = " +
      s"$normalIO.ensureFixedContents($contents);")
  }

  override def attrProcess(proc: ProcessExpr, varSrc: Identifier, varDest: Identifier): Unit = {
    val srcName = privateMemberName(varSrc)
    val destName = privateMemberName(varDest)

    proc match {
      case ProcessXor(xorValue) =>
        val procName = translator.detectType(xorValue) match {
          case _: IntType => "processXorOne"
          case _: BytesType => "processXorMany"
        }
        out.puts(s"$destName = $kstreamName.$procName($srcName, ${expression(xorValue)});")
      case ProcessZlib =>
        out.puts(s"$destName = $kstreamName.processZlib($srcName);")
      case ProcessRotate(isLeft, rotValue) =>
        val expr = if (isLeft) {
          expression(rotValue)
        } else {
          s"8 - (${expression(rotValue)})"
        }
        out.puts(s"$destName = $kstreamName.processRotateLeft($srcName, $expr, 1);")
    }
  }

  override def allocateIO(varName: Identifier, rep: RepeatSpec): String = {
    val langName = idToStr(varName)
    val memberCall = privateMemberName(varName)

    val ioName = s"_io_$langName"

    val args = rep match {
      case RepeatEos | RepeatUntil(_) => s"$memberCall[$memberCall.length - 1]"
      case RepeatExpr(_) => s"$memberCall[i]"
      case NoRepeat => memberCall
    }

    out.puts(s"var $ioName = new $kstreamName($args);")
    ioName
  }

  override def useIO(ioEx: expr): String = {
    out.puts(s"var io = ${expression(ioEx)};")
    "io"
  }

  override def pushPos(io: String): Unit =
    out.puts(s"var _pos = $io.pos;")

  override def seek(io: String, pos: Ast.expr): Unit =
    out.puts(s"$io.seek(${expression(pos)});")

  override def popPos(io: String): Unit =
    out.puts(s"$io.seek(_pos);")

  override def alignToByte(io: String): Unit =
    out.puts(s"$io.alignToByte();")

  override def attrDebugStart(attrId: Identifier, attrType: DataType, io: Option[String], rep: RepeatSpec): Unit = {
    if (!attrDebugNeeded(attrId))
      return

    val debugName = attrDebugName(attrId, rep, false)

    val ioProps = io match {
      case None => ""
      case Some(x) => s"start: $x.pos, ioOffset: $x._byteOffset"
    }

    val enumNameProps = attrType match {
      case t: EnumType => s"""enumName: \"${types2class(t.enumSpec.get.name)}\""""
      case _ => ""
    }

    out.puts(s"$debugName = { $ioProps${if (ioProps != "" && enumNameProps != "") ", " else ""}$enumNameProps };")
  }

  override def attrDebugEnd(attrId: Identifier, attrType: DataType, io: String, rep: RepeatSpec): Unit = {
    if (!attrDebugNeeded(attrId))
      return
    val debugName = attrDebugName(attrId, rep, true)

    out.puts(s"$debugName.end = $io.pos;")
  }

  override def condIfHeader(expr: expr): Unit = {
    out.puts(s"if (${expression(expr)}) {")
    out.inc
  }

  override def condIfFooter(expr: expr): Unit = {
    out.dec
    out.puts("}")
  }

  override def condRepeatEosHeader(id: Identifier, io: String, dataType: DataType, needRaw: Boolean): Unit = {
    if (needRaw)
      out.puts(s"${privateMemberName(RawIdentifier(id))} = [];")
    out.puts(s"${privateMemberName(id)} = [];")
    if (debug)
      out.puts(s"this._debug.${idToStr(id)}.arr = [];")
    out.puts(s"while (!$io.isEof()) {")
    out.inc
  }

  override def handleAssignmentRepeatEos(id: Identifier, expr: String): Unit = {
    out.puts(s"${privateMemberName(id)}.push($expr);")
  }

  override def condRepeatEosFooter: Unit = {
    out.dec
    out.puts("}")
  }

  override def condRepeatExprHeader(id: Identifier, io: String, dataType: DataType, needRaw: Boolean, repeatExpr: expr): Unit = {
    if (needRaw)
      out.puts(s"${privateMemberName(RawIdentifier(id))} = new Array(${expression(repeatExpr)});")
    out.puts(s"${privateMemberName(id)} = new Array(${expression(repeatExpr)});")
    if (debug)
      out.puts(s"this._debug.${idToStr(id)}.arr = new Array(${expression(repeatExpr)});")
    out.puts(s"for (var i = 0; i < ${expression(repeatExpr)}; i++) {")
    out.inc
  }

  override def handleAssignmentRepeatExpr(id: Identifier, expr: String): Unit = {
    out.puts(s"${privateMemberName(id)}[i] = $expr;")
  }

  override def condRepeatExprFooter: Unit = {
    out.dec
    out.puts("}")
  }

  override def condRepeatUntilHeader(id: Identifier, io: String, dataType: DataType, needRaw: Boolean, untilExpr: expr): Unit = {
    if (needRaw)
      out.puts(s"${privateMemberName(RawIdentifier(id))} = []")
    out.puts(s"${privateMemberName(id)} = []")
    if (debug)
      out.puts(s"this._debug.${idToStr(id)}.arr = [];")
    out.puts("do {")
    out.inc
  }

  override def handleAssignmentRepeatUntil(id: Identifier, expr: String, isRaw: Boolean): Unit = {
    val tmpName = translator.doName(if (isRaw) Identifier.ITERATOR2 else Identifier.ITERATOR)
    out.puts(s"var $tmpName = $expr;")
    out.puts(s"${privateMemberName(id)}.push($tmpName);")
  }

  override def condRepeatUntilFooter(id: Identifier, io: String, dataType: DataType, needRaw: Boolean, untilExpr: expr): Unit = {
    typeProvider._currentIteratorType = Some(dataType)
    out.dec
    out.puts(s"} while (!(${expression(untilExpr)}));")
  }

  override def handleAssignmentSimple(id: Identifier, expr: String): Unit = {
    out.puts(s"${privateMemberName(id)} = $expr;")
  }

  override def handleAssignmentTempVar(dataType: DataType, id: String, expr: String): Unit =
    out.puts(s"var $id = $expr;")

  override def parseExpr(dataType: DataType, io: String, defEndian: Option[FixedEndian]): String = {
    dataType match {
      case t: ReadableType =>
        s"$io.read${Utils.capitalize(t.apiCall(defEndian))}()"
      case blt: BytesLimitType =>
        s"$io.readBytes(${expression(blt.size)})"
      case _: BytesEosType =>
        s"$io.readBytesFull()"
      case BytesTerminatedType(terminator, include, consume, eosError, _) =>
        s"$io.readBytesTerm($terminator, $include, $consume, $eosError)"
      case BitsType1 =>
        s"$io.readBitsInt(1) != 0"
      case BitsType(width: Int) =>
        s"$io.readBitsInt($width)"
      case t: UserType =>
        val addArgs = if (t.isOpaque) "" else ", this, this._root"
        val addEndian = t.classSpec.get.meta.endian match {
          case Some(InheritedEndian) => ", this._is_le"
          case _ => ""
        }
        s"new ${type2class(t.name.last)}($io$addArgs$addEndian)"
    }
  }

  override def bytesPadTermExpr(expr0: String, padRight: Option[Int], terminator: Option[Int], include: Boolean) = {
    val expr1 = padRight match {
      case Some(padByte) => s"$kstreamName.bytesStripRight($expr0, $padByte)"
      case None => expr0
    }
    val expr2 = terminator match {
      case Some(term) => s"$kstreamName.bytesTerminate($expr1, $term, $include)"
      case None => expr1
    }
    expr2
  }

  override def userTypeDebugRead(id: String): Unit = {
    out.puts(s"$id._read();")
  }

  /**
    * Designates switch mode. If false, we're doing real switch-case for this
    * attribute. If true, we're doing if-based emulation.
    */
  var switchIfs = false

  val NAME_SWITCH_ON = Ast.expr.Name(Ast.identifier(Identifier.SWITCH_ON))

  override def switchStart(id: Identifier, on: Ast.expr): Unit = {
    val onType = translator.detectType(on)
    typeProvider._currentSwitchType = Some(onType)

    // Determine switching mode for this construct based on type
    switchIfs = onType match {
      case _: IntType | _: BooleanType | _: EnumType | _: StrType => false
      case _ => true
    }

    if (switchIfs) {
      out.puts("{")
      out.inc
      out.puts(s"var ${expression(NAME_SWITCH_ON)} = ${expression(on)};")
    } else {
      out.puts(s"switch (${expression(on)}) {")
    }
  }

  def switchCmpExpr(condition: Ast.expr): String =
    expression(
      Ast.expr.Compare(
        NAME_SWITCH_ON,
        Ast.cmpop.Eq,
        condition
      )
    )

  override def switchCaseFirstStart(condition: Ast.expr): Unit = {
    if (switchIfs) {
      out.puts(s"if (${switchCmpExpr(condition)}) {")
      out.inc
    } else {
      switchCaseStart(condition)
    }
  }

  override def switchCaseStart(condition: Ast.expr): Unit = {
    if (switchIfs) {
      out.puts(s"else if (${switchCmpExpr(condition)}) {")
      out.inc
    } else {
      out.puts(s"case ${expression(condition)}:")
      out.inc
    }
  }

  override def switchCaseEnd(): Unit = {
    if (switchIfs) {
      out.dec
      out.puts("}")
    } else {
      out.puts("break;")
      out.dec
    }
  }

  override def switchElseStart(): Unit = {
    if (switchIfs) {
      out.puts("else {")
      out.inc
    } else {
      out.puts("default:")
      out.inc
    }
  }

  override def switchEnd(): Unit = {
    if (switchIfs)
      out.dec
    out.puts("}")
  }

  override def instanceHeader(className: List[String], instName: InstanceIdentifier, dataType: DataType): Unit = {
    out.puts(s"Object.defineProperty(${type2class(className.last)}.prototype, '${publicMemberName(instName)}', {")
    out.inc
    out.puts("get: function() {")
    out.inc
  }

  override def instanceFooter: Unit = {
    out.dec
    out.puts("}")
    out.dec
    out.puts("});")
  }

  override def instanceCheckCacheAndReturn(instName: InstanceIdentifier): Unit = {
    out.puts(s"if (${privateMemberName(instName)} !== undefined)")
    out.inc
    instanceReturn(instName)
    out.dec
  }

  override def instanceReturn(instName: InstanceIdentifier): Unit = {
    out.puts(s"return ${privateMemberName(instName)};")
  }

  override def enumDeclaration(curClass: List[String], enumName: String, enumColl: Seq[(Long, String)]): Unit = {
    out.puts(s"${type2class(curClass.last)}.${type2class(enumName)} = Object.freeze({")
    out.inc
    enumColl.foreach { case (id, label) =>
      out.puts(s"${enumValue(enumName, label)}: $id,")
    }
    out.puts
    enumColl.foreach { case (id, label) =>
      out.puts(s"""$id: "${enumValue(enumName, label)}",""")
    }
    out.dec
    out.puts("});")
    out.puts
  }

  def enumValue(enumName: String, label: String) = label.toUpperCase

  override def debugClassSequence(seq: List[AttrSpec]) = {
    //val seqStr = seq.map((attr) => "\"" + idToStr(attr.id) + "\"").mkString(", ")
    //out.puts(s"SEQ_FIELDS = [$seqStr]")
  }

  def idToStr(id: Identifier): String = {
    id match {
      case SpecialIdentifier(name) => name
      case NamedIdentifier(name) => Utils.lowerCamelCase(name)
      case NumberedIdentifier(idx) => s"_${NumberedIdentifier.TEMPLATE}$idx"
      case InstanceIdentifier(name) => s"_m_${Utils.lowerCamelCase(name)}"
      case RawIdentifier(innerId) => "_raw_" + idToStr(innerId)
    }
  }

  override def privateMemberName(id: Identifier): String = s"this.${idToStr(id)}"

  override def publicMemberName(id: Identifier): String = {
    id match {
      case NamedIdentifier(name) => Utils.lowerCamelCase(name)
      case InstanceIdentifier(name) => Utils.lowerCamelCase(name)
    }
  }

  private
  def attrDebugNeeded(attrId: Identifier) = attrId match {
    case _: NamedIdentifier | _: NumberedIdentifier | _: InstanceIdentifier => true
    case _: RawIdentifier | _: SpecialIdentifier => false
  }

  def attrDebugName(attrId: Identifier, rep: RepeatSpec, end: Boolean) = {
    val arrIndexExpr = rep match {
      case NoRepeat => ""
      case _: RepeatExpr => ".arr[i]"
      case RepeatEos | _: RepeatUntil => s".arr[${privateMemberName(attrId)}.length${if (end) " - 1" else ""}]"
    }

    s"this._debug.${idToStr(attrId)}$arrIndexExpr"
  }
}

object JavaScriptCompiler extends LanguageCompilerStatic
  with UpperCamelCaseClasses
  with StreamStructNames {
  override def getCompiler(
    tp: ClassTypeProvider,
    config: RuntimeConfig
  ): LanguageCompiler = new JavaScriptCompiler(tp, config)

  override def kstreamName: String = "KaitaiStream"

  // FIXME: probably KaitaiStruct will emerge some day in JavaScript runtime, but for now it is unused
  override def kstructName: String = ???

  def types2class(types: List[String]): String =
    types.map(JavaScriptCompiler.type2class).mkString(".")
}
