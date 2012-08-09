package Chisel
import scala.collection.mutable.ArrayBuffer
import scala.collection.mutable.Stack
import scala.collection.mutable.Queue
import Component._
import Literal._
import Node._
import ChiselError._
  
object Enum {
  def apply(l: List[Symbol]) = (l zip (Range(0, l.length, 1).map(x => UFix(x, sizeof(l.length-1))))).toMap;
  def apply(l: Symbol *) = (l.toList zip (Range(0, l.length, 1).map(x => UFix(x, sizeof(l.length-1))))).toMap;
  def apply[T <: Bits](n: Int)(gen: => T) = (Range(0, n, 1).map(x => (Lit(x, sizeof(n-1))(gen)))).toList;
}

object when {
  def execWhen(cond: Bool)(block: => Unit) = {
    conds.push(conds.top && cond);
    block;
    conds.pop(); 
  }
  def apply(cond: Bool)(block: => Unit) = {
    execWhen(cond){ block }
    new when(cond);
  }
}
class when (prevCond: Bool) {
  def elsewhen (cond: Bool)(block: => Unit) = {
    when.execWhen(!prevCond && cond){ block }
    new when(prevCond || cond);
  }
  def otherwise (block: => Unit) = {
    when.execWhen(!prevCond){ block }
  }
}

object unless {
  def apply(c: Bool)(block: => Unit) = 
    when (!c) { block }
}

object otherwise {
  def apply(block: => Unit) = 
    when (Bool(true)) { block }
}
object switch {
  def apply(c: Bits)(block: => Unit) = {
    keys.push(c); 
    block; 
    keys.pop();
  }
}
object is {
  def apply(v: Bits)(block: => Unit) = {
    if (keys.length == 0) 
      println("NO KEY SPECIFIED");
    else {
      val c = keys(0) === v;
      when (c) { block; }
    }
  }
}

class TestIO(val format: String, val args: Seq[Data] = null)

object Scanner {
  def apply (format: String, args: Data*) = 
    new TestIO(format, args.toList);
}
object Printer {
  def apply (format: String, args: Data*) = 
    new TestIO(format, args.toList);
}

object withFame1 {
  def apply()(f: => Component) = {
    val x = isFame1; 
    isFame1 = true; 
    val c = f; 
    c.io.elements
    c.io.elementsCache += (("is_fire", c.isFire))
    isFame1 = x; 
    c
  }
}
 
object chiselMain {
  def readArgs(args: Array[String]) = {
    var i = 0;
    while (i < args.length) {
      val arg = args(i);
      arg match {
        case "--Wall" => {
          saveWidthWarnings = true
          saveConnectionWarnings = true
          saveComponentTrace = true
        }
        case "--Wwidth" => saveWidthWarnings = true
        case "--Wconnection" => saveConnectionWarnings = true
        case "--Wcomponent" => saveComponentTrace = true
        case "--noCombLoop" => dontFindCombLoop = true
        case "--genHarness" => isGenHarness = true; 
        case "--debug" => isDebug = true; 
        case "--ioDebug" => isIoDebug = true; 
        case "--noIoDebug" => isIoDebug = false; 
        case "--clockGatingUpdates" => isClockGatingUpdates = true; 
        case "--clockGatingUpdatesInline" => isClockGatingUpdatesInline = true; 
        case "--folding" => isFolding = true; 
        case "--vcd" => isVCD = true;
        case "--v" => backend = new VerilogBackend
        case "--moduleNamePrefix" => moduleNamePrefix = args(i+1); i += 1
        case "--inlineMem" => isInlineMem = true;
        case "--noInlineMem" => isInlineMem = false;
        case "--backend" => {
          if (args(i+1) == "v")
            backend = new VerilogBackend
          else if (args(i+1) == "c")
            backend = new CppBackend
          else if (args(i+1) == "fpga")
            backend = new FPGABackend
          else
            backend = Class.forName(args(i+1)).newInstance.asInstanceOf[Backend]
          i += 1
        }
        case "--compile" => isCompiling = true
        case "--test" => isTesting = true;
        case "--targetDir" => targetDir = args(i+1); i += 1;
        case "--include" => includeArgs = splitArg(args(i+1)); i += 1;
        case any => println("UNKNOWN CONSOLE ARG");
      }
      i += 1;
    }
  }

  def apply[T <: Component]
      (args: Array[String], gen: () => T, 
       scanner: T => TestIO = null, printer: T => TestIO = null, ftester: T => Tester[T] = null): T = {
    initChisel();
    readArgs(args)

    val c = gen();
    if (scanner != null) {
      val s = scanner(c);
      scanArgs  ++= s.args;
      for (a <- s.args) a.isScanArg = true
      scanFormat  = s.format;
    }
    if (printer != null) {
      val p = printer(c);
      printArgs   ++= p.args;
      for(a <- p.args) a.isPrintArg = true
      printFormat   = p.format;
    }
    if (ftester != null) {
      tester = ftester(c)
    }
    backend.transform(c)
    backend.elaborate(c)
    if (isCompiling && isGenHarness) backend.compile(c)
    if (isTesting) tester.tests()
    c
  }
}

object throwException {
  def apply(s: String) = {
    val xcpt = new Exception(s)
    val st = xcpt.getStackTrace
    val usrStart = findFirstUserInd(st)
    val usrEnd = if(usrStart == 0) st.length else usrStart + 1
    xcpt.setStackTrace(st.slice(usrStart, usrEnd))
    throw xcpt    
  }
}

object chiselMainTest {
  def apply[T <: Component](args: Array[String], gen: () => T)(tester: T => Tester[T]): T = 
    chiselMain(args, gen, null, null, tester)
}

trait proc extends Node {
  var isDefaultNeeded = true;
  var updates = new Queue[(Bool, Node)];
  def genCond() = conds.top
  def genDelayCond() = {
    val c = conds.top
    if (isFame1) fame1fire && c else c;
  }
  def genMuxes(default: Node) = {
    if (updates.length == 0) {
      if (inputs.length == 0 || inputs(0) == null){

	ChiselErrors += ChiselError({"NO UPDATES ON " + this}, this); 
      }
    } else {
      val (lastCond, lastValue) = updates.front;
      if (isDefaultNeeded && default == null && !lastCond.isTrue) {
        ChiselErrors += ChiselError({"NO DEFAULT SPECIFIED FOR WIRE: " + this}, this)
      }
      val (start, firstValue) = 
        if (default != null) 
          (0, default)
        else 
          (1, lastValue)
      if(inputs.length > 0)
	inputs(0) = firstValue;
      else
	inputs   += firstValue;

      var startCond: Bool = null
      def isEquals(x: Node, y: Node): Boolean = {
        if(x.litOf != null && y.litOf != null)
          x.litOf.value == y.litOf.value
        else
          x.equals(y)
      }

      for (i <- start until updates.size) {
        val (cond, value) = updates(i);
        if(i == updates.size-1 || !isEquals(updates(i+1)._2, value)) {
          if(startCond == null) {
            inputs(0) = Multiplex(cond, value, inputs(0));
          } else {
            inputs(0) = Multiplex(startCond || cond, value, inputs(0))
            startCond = null
          }
        } else {
          if(startCond == null)
            startCond = cond
          else
            startCond = startCond || cond
        }
      }
    }
  }
  def procAssign(src: Node);
  procs += this;
}

trait nameable {
  var name: String = "";
  var named = false;
}

abstract class BlackBox extends Component {
  parent.blackboxes += this;
  var moduleNameSet = false;

  def setVerilogParameters(string: String) = 
    this.asInstanceOf[Component].verilog_parameters = string;

  override def name_it() = {
    if(!moduleNameSet) {
      val cname = getClass().getName();
      val dotPos = cname.lastIndexOf('.');
      moduleName = if (dotPos >= 0) cname.substring(dotPos+1) else cname;
    }
  }
  def setName(name: String) = {moduleName = name; moduleNameSet = true}
}


class Delay extends Node {
  override def isReg = true;
}

object Log2 {
  def apply (mod: UFix, n: Int): UFix = {
    backend match {
      case x: CppBackend => {
        val log2 = new Log2()
        log2.init("", fixWidth(sizeof(n)), mod)
        log2.setTypeNode(UFix())
      }
      case _ => {
        var res = UFix(0);
        for (i <- 1 to n)
          res = Mux(mod(i), UFix(i, sizeof(n)), res);
        res
      }
    }
  }
}

class Log2 extends Node {
  override def toString: String = "LOG2(" + inputs(0) + ")";
}
