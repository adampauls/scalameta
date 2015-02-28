package org.scalameta.ast

import scala.language.experimental.macros
import scala.annotation.StaticAnnotation
import scala.reflect.macros.blackbox.Context

object internal {
  trait Ast extends org.scalameta.adt.Internal.Adt
  class root extends StaticAnnotation
  class branch extends StaticAnnotation
  class astClass extends StaticAnnotation
  class astCompanion extends StaticAnnotation
  class auxiliary extends StaticAnnotation
  class registry(fullNames: List[String]) extends StaticAnnotation

  def hierarchyCheck[T]: Unit = macro Macros.hierarchyCheck[T]
  def productPrefix[T]: String = macro Macros.productPrefix[T]
  def loadField[T](f: T): Unit = macro Macros.loadField
  def storeField[T](f: T, v: T): Unit = macro Macros.storeField
  def initField[T](f: T): T = macro Macros.initField
  def initParam[T](f: T): T = macro Macros.initField

  case class RegistryAttachment(fullNames: List[String])
  def register[T]: Unit = macro Macros.register[T]
  def buildRegistry[T]: List[String] = macro Macros.buildRegistry[T]

  class Macros(val c: Context) extends org.scalameta.adt.AdtReflection {
    val u: c.universe.type = c.universe
    val mirror: u.Mirror = c.mirror
    import c.universe._
    import c.internal._
    import decorators._
    import definitions._
    def hierarchyCheck[T](implicit T: c.WeakTypeTag[T]): c.Tree = {
      val sym = T.tpe.typeSymbol.asClass
      val designation = if (sym.isRoot) "root" else if (sym.isBranch) "branch" else if (sym.isLeaf) "leaf" else ???
      val roots = sym.baseClasses.filter(_.isRoot)
      if (roots.length == 0 && sym.isLeaf) c.abort(c.enclosingPosition, s"rootless leaf is disallowed")
      else if (roots.length > 1) c.abort(c.enclosingPosition, s"multiple roots for a $designation: " + (roots.map(_.fullName).init.mkString(", ")) + " and " + roots.last.fullName)
      val root = roots.headOption.getOrElse(NoSymbol)
      sym.baseClasses.map(_.asClass).foreach{bsym =>
        val exempt =
          bsym.isModuleClass ||
          bsym == ObjectClass ||
          bsym == AnyClass ||
          bsym == symbolOf[scala.Serializable] ||
          bsym == symbolOf[java.io.Serializable] ||
          bsym == symbolOf[scala.Product] ||
          bsym == symbolOf[scala.Equals] ||
          root.info.baseClasses.contains(bsym)
        if (!exempt && !bsym.isRoot && !bsym.isBranch && !bsym.isLeaf) c.abort(c.enclosingPosition, s"outsider parent of a $designation: ${bsym.fullName}")
        // NOTE: turned off because we can't have @ast hierarchy sealed anymore
        // hopefully, in the future we'll find a way to restore sealedness
        // if (!exempt && !bsym.isSealed && !bsym.isFinal) c.abort(c.enclosingPosition, s"unsealed parent of a $designation: ${bsym.fullName}")
      }
      q"()"
    }
    def register[T](implicit T: c.WeakTypeTag[T]): c.Tree = {
      // NOTE: unfortunately, we can't call Symbol.addChild manually here
      // if we do that, then pattern matcher will go nuts
      // val directParents = T.tpe.typeSymbol.info.asInstanceOf[ClassInfoType].parents
      // directParents.foreach(ptpe => {
      //   val poweru = u.asInstanceOf[scala.reflect.internal.SymbolTable]
      //   val psym = ptpe.typeSymbol.asInstanceOf[poweru.Symbol]
      //   psym.addChild(T.tpe.typeSymbol.asInstanceOf[poweru.Symbol])
      // })
      val registry = c.mirror.staticModule("scala.meta.internal.ast.Registry")
      val att0 = registry.attachments.get[RegistryAttachment].getOrElse(new RegistryAttachment(Nil))
      val att1 = att0.copy(fullNames = att0.fullNames :+ T.tpe.typeSymbol.fullName)
      registry.updateAttachment(att1)
      q"()"
    }
    def buildRegistry[T](implicit T: c.WeakTypeTag[T]): c.Tree = {
      val att = T.tpe.typeSymbol.asClass.module.attachments.get[RegistryAttachment]
      att match {
        case Some(RegistryAttachment(fullNames)) => q"$fullNames"
        case _ => c.abort(c.enclosingPosition, "fatal error building scala.meta registry")
      }
    }
    def productPrefix[T](implicit T: c.WeakTypeTag[T]): c.Tree = {
      q"${T.tpe.typeSymbol.asLeaf.prefix}"
    }
    def loadField(f: c.Tree): c.Tree = {
      val q"this.$finternalName" = f
      def uncapitalize(s: String) = if (s.length == 0) "" else { val chars = s.toCharArray; chars(0) = chars(0).toLower; new String(chars) }
      val fname = TermName(finternalName.toString.stripPrefix("_"))
      def lazyLoad(fn: c.Tree => c.Tree) = {
        val assertionMessage = s"internal error when initializing ${c.internal.enclosingOwner.owner.name}.$fname"
        q"""
          if ($f == null) {
            // there's not much sense in using org.scalameta.invariants.require here
            // because when the assertion trips, the tree is most likely in inconsistent state
            // which will either lead to useless printouts or maybe even worse errors
            _root_.scala.Predef.require(this.internalPrototype != null, $assertionMessage)
            $f = ${fn(q"this.internalPrototype.$fname")}
          }
        """
      }
      f.tpe.finalResultType match {
        case Primitive(tpe) => q"()"
        case Tree(tpe) => lazyLoad(pf => q"$pf.internalCopy(prototype = $pf, parent = this)")
        case OptionTree(tpe) => lazyLoad(pf => q"$pf.map(el => el.internalCopy(prototype = el, parent = this))")
        case OptionSeqTree(tpe) => lazyLoad(pf => q"$pf.map(_.map(el => el.internalCopy(prototype = el, parent = this)))")
        case SeqTree(tpe) => lazyLoad(pf => q"$pf.map(el => el.internalCopy(prototype = el, parent = this))")
        case SeqSeqTree(tpe) => lazyLoad(pf => q"$pf.map(_.map(el => el.internalCopy(prototype = el, parent = this)))")
      }
    }
    def storeField(f: c.Tree, v: c.Tree): c.Tree = {
      f.tpe.finalResultType match {
        case Primitive(tpe) => q"()"
        case Tree(tpe) => q"$f = $v.internalCopy(prototype = $v, parent = node)"
        case OptionTree(tpe) => q"$f = $v.map(el => el.internalCopy(prototype = el, parent = node))"
        case OptionSeqTree(tpe) => q"$f = $v.map(_.map(el => el.internalCopy(prototype = el, parent = node)))"
        case SeqTree(tpe) => q"$f = $v.map(el => el.internalCopy(prototype = el, parent = node))"
        case SeqSeqTree(tpe) => q"$f = $v.map(_.map(el => el.internalCopy(prototype = el, parent = node)))"
        case tpe => c.abort(c.enclosingPosition, s"unsupported field type $tpe")
      }
    }
    def initField(f: c.Tree): c.Tree = {
      f.tpe.finalResultType match {
        case Primitive(tpe) => q"$f"
        case Tree(tpe) => q"null"
        case OptionTree(tpe) => q"null"
        case OptionSeqTree(tpe) => q"null"
        case SeqTree(tpe) => q"null"
        case SeqSeqTree(tpe) => q"null"
        case tpe => c.abort(c.enclosingPosition, s"unsupported field type $tpe")
      }
    }
    private object Primitive {
      def unapply(tpe: Type): Option[Type] = {
        if (tpe =:= typeOf[String] ||
            tpe =:= typeOf[scala.Symbol] ||
            ScalaPrimitiveValueClasses.contains(tpe.typeSymbol)) Some(tpe)
        else if (tpe.typeSymbol == OptionClass && Primitive.unapply(tpe.typeArgs.head).nonEmpty) Some(tpe)
        else if (tpe.baseClasses.contains(c.mirror.staticClass("scala.meta.internal.hygiene.Denotation"))) Some(tpe)
        else if (tpe.baseClasses.contains(c.mirror.staticClass("scala.meta.internal.hygiene.Sigma"))) Some(tpe)
        else None
      }
    }
    private object Tree {
      def unapply(tpe: Type): Option[Type] = {
        if (tpe <:< c.mirror.staticClass("scala.meta.Tree").asType.toType) Some(tpe)
        else None
      }
    }
    private object SeqTree {
      def unapply(tpe: Type): Option[Type] = {
        if (tpe.typeSymbol == c.mirror.staticClass("scala.collection.immutable.Seq")) {
          tpe.typeArgs match {
            case Tree(tpe) :: Nil => Some(tpe)
            case _ => None
          }
        } else None
      }
    }
    private object SeqSeqTree {
      def unapply(tpe: Type): Option[Type] = {
        if (tpe.typeSymbol == c.mirror.staticClass("scala.collection.immutable.Seq")) {
          tpe.typeArgs match {
            case SeqTree(tpe) :: Nil => Some(tpe)
            case _ => None
          }
        } else None
      }
    }
    private object OptionTree {
      def unapply(tpe: Type): Option[Type] = {
        if (tpe.typeSymbol == c.mirror.staticClass("scala.Option")) {
          tpe.typeArgs match {
            case Tree(tpe) :: Nil => Some(tpe)
            case _ => None
          }
        } else None
      }
    }
    private object OptionSeqTree {
      def unapply(tpe: Type): Option[Type] = {
        if (tpe.typeSymbol == c.mirror.staticClass("scala.Option")) {
          tpe.typeArgs match {
            case SeqTree(tpe) :: Nil => Some(tpe)
            case _ => None
          }
        } else None
      }
    }
  }
}
