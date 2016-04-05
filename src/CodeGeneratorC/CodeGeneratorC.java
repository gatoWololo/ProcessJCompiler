package CodeGeneratorC;

import AST.*;
import Utilities.*;
import Utilities.Error;
import org.stringtemplate.v4.*;
import java.io.*;
import java.util.*;
import NameCollector.*;

/**
 * Code Geneartor turns processJ code to equivalent C code using using
 * parse tree by visiting each node. It then recusively builds the program
 * through recursion returning strings rendered by the string template.
 * Notice sometimes we return String[] if there is multiple statements.
 * General rules of thumb:
 * 1) Anything that is wrapped in a sequence will be passed up as an String[].
 *    it will then be passed to ST as an String[] where the equivalent ST rule
 *    will have a <X;separator = " S"> where S is a string. Therefore the sequence
 *    be delimited by say newlines or commas depending on the proper context.
 * 2) Optional parameters are implemented using <if(X)> <endif> blocks in the ST.
 */
public class CodeGeneratorC <T extends Object> extends Visitor<T> {
  //====================================================================================
  /** Relative location of our group string template file.*/
  private final String grammarStFile = "src/StringTemplates/grammarTemplates.stg";
  /** Name of the parameter of type Workspace that all function needs*/
  private final String globalWorkspace = "WP";
  /** Object containing a group of  string templates. */
  private STGroup group;
  /**
   * We must know the name of the last function of the program as this is
   * the function that will be called by the CCSP runtime to start running
   * the program, this is the last function on the file. */
  private String lastFunction = null;
  private Boolean inLastFunction = false;
  /**
   * This Hash Table is used to create temporary functions with unique names. Everytime
   * we hit a ProcDecl we know we have a new function and are looking at a new scope,
   * hence we might have a ParBlock{...} so we create a new entry in this function and
   * and set the amount to 0 from here for every statement that needs to be wrapped in
   * a function we give it a name of the form <NameOfFunction>ParBlockStmt<counterValue>.
   */
  private Hashtable<String, Integer> parBlockStmtCounts;
  /**
   * We must know when we are inside a ParBlock as this means we have to do eveything
   * with pointers to NameExpr instead of actual variales.
   */
  private Boolean inParBlock = false;
  /**
   * Keeps track of the current function we are inside of. This is done for ParBlock
   * uses this to generate the name of our <...>ParBlockStmt<...> function name. see
   * @parBlockstmtCounts for more info.
   */
  private String currentFunction = null;
  /**
   * These two variables are responsible for holding the auto generated functions
   * created by ParBlocks, one holds the prototypes for the top of the program while the
   * other holds the actual declaration. Then at VisitCompilation they are added to our
   * template.
   */
  private LinkedList<String> parBlockPrototypes = null;
  private LinkedList<String> parBlockProcs = null;

  private final String parWsName = "parWs";

  private Boolean hasArray = false;
  private int arrayDepth = 0;
  private String arrayName = "";

  /** This table is used to allocate the stack size needed per function. */
  Hashtable<String, Integer> sizePerFunction;
  //====================================================================================
  /**
   * This is the constructor to be called the first time around to create the initial
   * code hence it requires no table of sizes.
   */
  public CodeGeneratorC(){
    Log.log("======================================");
    Log.log("* C O D E   G E N E R A T O R  ( C )  *");
    Log.log("*        F I R S T  P A S S           *");
    Log.log("======================================");

    //Load our string templates from specified directory.
    this.group = new STGroupFile(grammarStFile);
    //Create hashtables!
    this.parBlockStmtCounts = new Hashtable();

    this.parBlockPrototypes = new LinkedList();
    this.parBlockProcs = new LinkedList();
    sizePerFunction = null;

    return;
  }
  //====================================================================================
  /**
   * This constructor is called for the second pass. It is used to allocate the final size
   * of the each stack. The functions that require manual stack allocation know who they are
   * they will look up their name in the sizePerFunction Hashtable and allocate the proper
   * size. These values were created by the AllocateStackSize class.
   * @param sizePerFunction: The size of the stack required by each function called.
   */
  public CodeGeneratorC(Hashtable<String, Integer> sizePerFunction){
    Log.log("======================================");
    Log.log("* C O D E   G E N E R A T O R  ( C )  *");
    Log.log("*       S E C O N D  P A S S          *");
    Log.log("======================================");

    //Load our string templates from specified directory.
    this.group = new STGroupFile(grammarStFile);
    //Create hashtables!
    this.parBlockStmtCounts = new Hashtable();

    this.parBlockPrototypes = new LinkedList();
    this.parBlockProcs = new LinkedList();
    this.sizePerFunction = sizePerFunction;

    return;
  }
  //====================================================================================
  // AltCase
  /**
   * The AltCase visitor produces the string that will go inside the case of the switch
   * statement. The equivalent C code must look something like:
   * case N:
   *   <Expression Statement which was our guard.>;
   *   <Rest of statements inside the stat block>;
   *   break;
   */
  public T visitAltCase(AltCase ac) {
    Log.log(ac.line + ": Visiting an AltCase");
    ST template = group.getInstanceOf("AltCase");

    //TODO preconditions.
    Statement caseExprStmt = ac.guard().guard();
    Statement stat = ac.stat();
    int caseNumber = ac.getCaseNumber();
    String caseExprStr;

    //We treat TimeoutStat differently:
    if(caseExprStmt instanceof TimeoutStat)
      caseExprStr = "/*Timeout do nothing here!*/";
    else
      //Two more posibilities for myExpr: SkipStat | ExprStat (Channel)
      caseExprStr = (String)caseExprStmt.visit(this);

    //Make the actual block of statements to run, we know this must always be a block so,
    //no need to check what is returned.
    String[] statementList = (String[])stat.visit(this);

    template.add("number", caseNumber);
    template.add("guardToDo", caseExprStr);
    template.add("statementList", statementList);

    return (T) template.render();
  }
  //====================================================================================
  // AltStat
  /**
   * Alt cases are also nontrivial. Granted, they are not hard. The main layout of any
   * alt case in CCSP looks like:
   * <timerAlt(..) | Alt(..)> //Initialization for Alternate statement.
   * <AltEnableChannel(..) | AltEnableTimer(..) | AltEnableSkip(..)> //Initialization
   *   of all cases that will partake in the alt statement.
   * <timerAltWait(...) | AltWait(...)> //Actual place where we will wait.
   * <AltDisableChannel(..) | AltDisableTimer(..) | AltDisableSkip(..) //UnInitialization
   * <switch( AltEnd(<workspaceName> ){
   *    <cases for switch>:
   *      <expression from guard>
   *      <statements from this condition>
   *    ...
   * }
   *
   * This is all! So we use our String Templates to build this shape.
   */
     //TODO: PRIALT
  public T visitAltStat(AltStat as) {
    Log.log(as.line + ": Visiting an AltStat");
    ST template = group.getInstanceOf("AltStat");
    Sequence<AltCase> altCaseList = as.body();
    int count = altCaseList.size();
    boolean hasTimeout = false;

    //If the alt uses timeout we must use a different function to invoke the alt.
    for(int i = 0; i < count; i++){
      AltCase altCase = altCaseList.getElementN(i);
      hasTimeout = caseIsTimeout(altCase);

      if(hasTimeout == true)
        break;
    }

    //Create the Alt() and AltWait().
    String altTypeStr = "NormalAltType";
    String waitTypeStr = "NormalWaitType";
    if(hasTimeout == true){
      altTypeStr = "TimerAltType";
      waitTypeStr = "TimerWaitType";
    }

    ST altTypeTemplate = group.getInstanceOf(altTypeStr);
    ST waitTypeTemplate = group.getInstanceOf(waitTypeStr);

    //These lists hold our strings representing the {"AltEnablechannel(<name>)",...}
    //for our alt statement.
    String[] enableList = createEnableDisable(altCaseList, true);
    String[] disableList = createEnableDisable(altCaseList, false);

    //Only thing left to do is to make the switch statement holding our cases to run.
    ST altEndTemplate = group.getInstanceOf("AltEnd");
    ST altSwitchTemplate = group.getInstanceOf("AltSwitch");

    //Create AltEnd part.
    altEndTemplate.add("globalWsName", globalWorkspace);
    String altEndStr = altEndTemplate.render();

    //Iterate over our children setting their number.
    for(int i = 0; i < count; i++){
     AltCase altCase = altCaseList.getElementN(i);
     altCase.setCaseNumber(i);
    }

    //Create all the case statements.
    String[] caseList = (String[])altCaseList.visit(this);

    //Now make our switch string.
    altSwitchTemplate.add("altEnd", altEndStr);
    altSwitchTemplate.add("caseList", caseList);
    String altSwitchStr = altSwitchTemplate.render();

    //Make our wait and alt templates.
    altTypeTemplate.add("globalWsName", globalWorkspace);
    waitTypeTemplate.add("globalWsName", globalWorkspace);

    //Make final string!
    template.add("altType", altTypeTemplate.render());
    template.add("enableChannelList", enableList);
    template.add("waitType", waitTypeTemplate.render());
    template.add("disableChannelList", disableList);
    template.add("switchStatement", altSwitchStr);

    return (T) template.render();
  }
  //====================================================================================
  // ArrayAccessExpr

  public T visitArrayAccessExpr(ArrayAccessExpr ae) {
    Log.log(ae.line + ": Visiting an ArrayAccessExpr!");
    ST accessTemplate = group.getInstanceOf("ArrayAccess");
    int myDepth;

    // top level array access
    if ((ae.target() instanceof NameExpr)) {
      String myArrayTarget = (String)ae.target().visit(this);
      String myIndex = (String)ae.index().visit(this);
      String typeName = getCDataType((ae.type).typeName());

      if (arrayDepth == 0) {
        return (T) ("((" + typeName + ")" + myArrayTarget + ".array)[" + myIndex + "]");
      }
      else {
        arrayName = myArrayTarget;
        myDepth = arrayDepth;
        accessTemplate.add("name", myArrayTarget);
        accessTemplate.add("index", myIndex);
        accessTemplate.add("dim", Integer.toString(myDepth));

        return (T) ("((" + typeName + ")" + myArrayTarget + ".array)[" + accessTemplate.render());
      }
    }
    else {
      if (arrayDepth == 0) {
        arrayDepth += 1;
        String myArrayTarget = (String)ae.target().visit(this);
        String myIndex = (String)ae.index().visit(this);
        arrayDepth = 0;

        return (T) (myArrayTarget + " + " + myIndex + "]");
      }
      else {
        myDepth = arrayDepth;
        arrayDepth += 1;
        String myArrayTarget = (String)ae.target().visit(this);
        String myIndex = (String)ae.index().visit(this);
        accessTemplate.add("name", arrayName);
        accessTemplate.add("index", myIndex);
        accessTemplate.add("dim", myDepth);
        return (T) (myArrayTarget + " + " + accessTemplate.render());
      }
    }
  }

  //====================================================================================
  // ArrayLiteral
  //====================================================================================
  // ArrayType

  public T visitArrayType(ArrayType at) {
    Log.log(at.line + ": Visiting an ArrayType!");
    String baseType = (String)at.baseType().visit(this);
    return (T) (baseType);
  }

  //====================================================================================
  // Assignment
  public T visitAssignment(Assignment as){
    Log.log(as.line + ": Visiting an Assignment");
    ST template = group.getInstanceOf("Assignment");

    if (as.right() instanceof NewArray) {
      hasArray = true;
      if (as.left() instanceof NameExpr) {
        arrayName = (String) as.left().visit(this);
        return as.right().visit(this);
      }
      else {
        // TODO: Extend NewArray to record_access and array_access
        Error.error("Cannot assign new array to non NameExpr, it is not supported at this time");
      }
    }

    String left = (String) as.left().visit(this);
    String right = (String) as.right().visit(this);
    String op = (String) as.opString();

    template.add("left",left);
    template.add("right",right);
    template.add("op",op);

    return (T) template.render();
  }
  //====================================================================================
  // BinaryExpr
  public T visitBinaryExpr(BinaryExpr be){
    Log.log(be.line + ": Visiting a Binary Expression");
    ST template = group.getInstanceOf("BinaryExpr");

    String left = (String) be.left().visit(this);
    String right = (String) be.right().visit(this);
    String op = (String) be.opString();

    //TODO: Add suport for string concatanation here.

    template.add("left",left);
    template.add("right",right);
    template.add("op",op);

    return (T) template.render();
  }
  //====================================================================================
  // Block
  public T visitBlock(Block bl){
    Log.log(bl.line + ": Visiting a Block");
    String[] statements = (String[]) bl.stats().visit(this);

    return (T) statements;
  }
  //====================================================================================
  // BreakStat //TODO: Add identifier option.
  public T visitBreakStat(BreakStat bs){
    Log.log(bs.line + ": Visiting a BreakStat");
    ST template = group.getInstanceOf("BreakStat");
    //Can be null.
    Name name = bs.target();

    if(name != null){
      String nameStr = (String) name.visit(this);
      //Add name option here.
    }

    return (T) template.render();
  }
  //====================================================================================
  // CastExpr
  public T visitCastExpr(CastExpr ce){
    Log.log(ce.line + ": Visiting a Cast Expression");
    ST template = group.getInstanceOf("CastExpr");
    //No node for type get actual string.
    String ct = ce.type().typeName();
    String expr = (String) ce.expr().visit(this);

    template.add("ct", ct);
    template.add("expr", expr);


    return (T) template.render();
  }
  //====================================================================================
  // ChannelType
  /**
   * TODO: Change channel implementation from Channel* as the default data type to
   * just Channel.
   * Channels are difficult as they have a type Channel, yet they are passed as pointers
   * when actually doing stuff. So to keep our logic clean and not worry whether this
   * is a Channel, or a Channel* inside a function we consider the only type to be
   * Channel*.
   */
  public T visitChannelType(ChannelType ct){
    Log.log(ct.line + ": Visiting a Channel Type!");
    return (T) "Channel*";
  }
  //====================================================================================
  // ChannelEndExpr
  public T visitChannelEndExpr(ChannelEndExpr ce){
    Log.log(ce.line + ": Visiting a Channel End Expression!");
    //TODO: Figure out what else could be in a ChannelEndExpr.
    String channel = (String) ce.channel().visit(this);

    return (T) channel;
  }
  //====================================================================================
  // ChannelEndType
  public T visitChannelEndType(ChannelEndType ct){
    Log.log(ct.line + ": Visiting a Channel End Type!");
    //C has no concept of ChannelEnd Types it is just the pointer to that channel.
    return (T) "Channel*";
  }
  //====================================================================================
  // ChannelReadExpr
  public T visitChannelReadExpr(ChannelReadExpr cr){
    Log.log(cr.line + ": Visiting ChannelReadExpr");
    //This needs to be a function call in C that takes one argument:
    //    ChanInInt(wordPointer,intIn, &x);
    //We will have to use a temp varible to achieve this with notation like:
    //    int x = t.read();
    //TODO: Extend to all possible types.
    ST template = null;
    Expression channelExpr = cr.channel();
    NameExpr channelNameExpr = null;
    //This is either a NameExpression (chan.read()) or a ChannelEndExpr (chan.read.read())
    if(channelExpr instanceof NameExpr)
      channelNameExpr = (NameExpr) channelExpr;
    else if(channelExpr instanceof ChannelEndExpr)
      channelNameExpr = (NameExpr)((ChannelEndExpr)channelExpr).channel();

    String channel = (String) channelNameExpr.visit(this);
    Type myType = null;


    //TODO: Clean this mess up.
    if(channelNameExpr.myDecl instanceof LocalDecl)
      //Figure out type of channel and do appropriate code generation based on this.
      myType = ((LocalDecl)channelNameExpr.myDecl).type();
    if(channelNameExpr.myDecl instanceof ParamDecl)
      //Figure out type of channel and do appropriate code generation based on this.
      myType = ((ParamDecl)channelNameExpr.myDecl).type();

    //Add multiple types for different things here: TODO add all types.
    //Posibility One: Timers!
    if( myType.isTimerType() ){
      template = group.getInstanceOf("ChannelReadExprTimer");
      template.add("globalWsName", globalWorkspace);
    }
    //Posibility Two: This is an actual end: chan<type>.read chan,
    //chan.read()
    else if( myType.isChannelEndType() ){
      Type baseType = ((ChannelEndType)myType).baseType();

      if( baseType.isIntegerType() || baseType.isBooleanType()){
        template = group.getInstanceOf("ChannelReadExprInt");
        template.add("globalWsName", globalWorkspace);
        template.add("channel", channel);
      }
      else{
        String errorMsg = "Unsupported type: %s for ChannelEndType!";
        String error = String.format(errorMsg, baseType.toString());
        Error.error(cr, error);
      }
    }
    //Posibility Three: This is a channel to be treated as an end to avoid
    //chan.read.read().
    else if( myType.isChannelType() ){
      Type baseType = ((ChannelType)myType).baseType();

      if( baseType.isIntegerType() || baseType.isBooleanType() ){
        template = group.getInstanceOf("ChannelReadExprInt");
        template.add("globalWsName", globalWorkspace);
        template.add("channel", channel);
      }
      else{
        String errorMsg = "Unsupported type: %s for ChannelEndType!";
        String error = String.format(errorMsg, baseType.toString());
        Error.error(cr, error);
      }
    }
    else{
      String errorMsg = "Unsupported type: %s for ChannelReadExpr.";
      String error = String.format(errorMsg, myType.typeName());
      Error.error(cr, error);
    }

    return (T) template.render();
  }
  //====================================================================================
  // ChannelWriteStat
  public T visitChannelWriteStat(ChannelWriteStat cw){
    Log.log(cw.line + ": Visiting a Channel Write Statement!");

    ST template = group.getInstanceOf("ChannelWriteStat");
    String expr = (String) cw.expr().visit(this);
    String channel = (String) cw.channel().visit(this);
    //TODO: template should be based on type of expression!
    //Only int works for now.
    template.add("globalWsName", globalWorkspace);
    template.add("channel", channel);
    template.add("expr", expr);

    return (T) template.render();
  }
  //====================================================================================
  // ClaimStat
  //====================================================================================
  // Compilation
  public T visitCompilation(Compilation c){
    Log.log(c.line + ": Visiting the Compilation");

    ST template = group.getInstanceOf("Compilation");
    LinkedList<String> prototypes = new LinkedList();

    //We add our function prototypes here as the C program will need them.
    Sequence<Type> typeDecls = c.typeDecls();

    //Iterate over the sequence only collecting the procType arguments. This is needed
    //to know the name of the last functions.
    for(Type type : typeDecls)
      if (type instanceof ProcTypeDecl){
        ProcTypeDecl current = (ProcTypeDecl)type;
        String prototypeName = getPrototypeString(current);

        prototypes.add(prototypeName);
        String name = current.name().getname();

        this.lastFunction = name;
      }

    //TODO add the pragmas, packageName and imports later!
    //Recurse to all children getting strings needed for this Class' Template.
    String[] typeDeclsStr = (String[]) c.typeDecls().visit(this);
    int stackSize = getSizeOfFunction(sizePerFunction, lastFunction);
    //Divide by four as we want the size in words not bytes.
    stackSize = (int) Math.ceil(stackSize / 4.0);

    //This is where functions are created as they are procedure type.
    template.add("prototypes", prototypes);
    template.add("typeDecls", typeDeclsStr);
    //ProcessJ is set so the last function in the file is called as the main,
    //we do that here by specifiying which function to call.
    template.add("functionToCall", lastFunction);
    template.add("parBlockPrototypes", parBlockPrototypes);
    template.add("stackSize", stackSize);
    template.add("parBlockProcs", parBlockProcs);

    if (hasArray == true)
      template.add("hasArray", "true");

    //Finally write the output to a file
    String finalOutput = template.render();
    writeToFile(finalOutput);
    Log.log("Output written to file codeGenerated.c");

    return (T) finalOutput;
  }
  //====================================================================================
  // ConstantDecl
  //====================================================================================
  // ContinueStat //TODO: add identifier option.
  public T visitContinueStat(ContinueStat cs){
    Log.log(cs.line + ": Visiting a ContinueStat");

    ST template = group.getInstanceOf("ContinueStat");
    //Can be null.
    Name name = cs.target();

    if(name != null){
      String nameStr = (String) name.visit(this);
      //Add name option here.
    }

    return (T) template.render();
  }
  //====================================================================================
  // DoStat
  //TODO: I think this will crash if we do:
  //  do
  //    <oneStat>
  //  while(<expr>;
  //Since this does not return a Strig[]
  public T visitDoStat(DoStat ds){
    Log.log(ds.line + ": Visiting a DoStat");

    ST template = group.getInstanceOf("DoStat");
    String[] stats = (String[]) ds.stat().visit(this);
    String expr = (String) ds.expr().visit(this);

    template.add("stat", stats);
    template.add("expr", expr);

    return (T) template.render();
  }
  //====================================================================================
  // ExprStat
  public T visitExprStat(ExprStat es){
    Log.log(es.line + ": Visiting a ExprStat");

    return (T) es.expr().visit(this);
  }
  //====================================================================================
  // ExternType
  //====================================================================================
  // ForStat
  public T visitForStat(ForStat fs){
    Log.log(fs.line + ": Visiting a ForStat");
    ST template = group.getInstanceOf("ForStat");

    String expr = (String) fs.expr().visit(this);
    Sequence<Statement> init = fs.init();
    Sequence<ExprStat> incr = fs.incr();
    String[] initStr = null;
    String[] incrStr = null;
    //TODO: Barriers

    //Depending whether there is curly brackets it may return an array, or maybe just
    //a single object. So we must check what it actually is!
    Object stats = fs.stats().visit(this);

    if(stats instanceof String[])
      template.add("stats", (String[]) stats);
    else
      template.add("stats", (String) stats);

    //Check for null >:(
    if(init != null)
      initStr = (String[]) init.visit(this);
    if(incr != null)
      incrStr = (String[]) incr.visit(this);

    template.add("init", initStr);
    template.add("incr", incrStr);
    template.add("expr", expr);

    return (T) template.render();
  }
  //====================================================================================
  //TimeoutStat
  public T visitTimeoutStat(TimeoutStat ts){
    Log.log(ts.line + ": Visiting TimeoutStat");
    ST template = group.getInstanceOf("TimeoutStat");
    //Not needed? TODO
    String timer = (String) ts.timer().visit(this);
    String delay = (String) ts.delay().visit(this);
    //TODO: add appropriate units as of know I believe it's in microseconds right now.
    //Also we need to figure out the proper semantics for this.

    template.add("globalWsName", globalWorkspace);
    template.add("delay", delay);


    return (T) template.render();
  }
  //====================================================================================
  // Guard
  public T visitGuard(Guard gd){
    Log.log(gd.line + ": Visiting Guard");

    Statement stmt = gd.guard();

    //Three posibilities for myExpr: SkipStat | TimeoutStat | ExprStat (Channel)
    if( stmt instanceof ExprStat){
      //Get Channel name. TODO make less WFT?
      Expression rightHandSide = ( (Assignment)(( (ExprStat)stmt ) ).expr() ).right();
      String name = ( (NameExpr)( ( (ChannelReadExpr)rightHandSide ).channel() ) )
        .toString();
      return (T) name;
    }

    if(stmt instanceof TimeoutStat){
      TimeoutStat timeoutStmt = (TimeoutStat)stmt;
      String name = ( (NameExpr)timeoutStmt.timer() ).toString();
      return (T) name;
    }
    //Else we don't care about the name so just return null...
    return null;
  }
  //====================================================================================
  // IfStat
  //TODO: We may want to change where we return either a String or a String[] to always
  //returnting String[] even if it only has one element...
  public T visitIfStat(IfStat is){
    Log.log(is.line + ": Visiting a ifStat");
    ST template = group.getInstanceOf("IfStat");

    String expr = (String) is.expr().visit(this);
    Statement elsePart = is.elsepart();
    Object elsePartStr = null;
    Object thenPart = is.thenpart().visit(this);

    template.add("expr", expr);

    //May be one statement or multiple statements.
    if(thenPart instanceof String[])
      template.add("thenPart", (String[]) thenPart);
    else
      template.add("thenPart", (String) thenPart);

    //May or may not be here!
    if(elsePart != null){
      elsePartStr = elsePart.visit(this);

      if(thenPart instanceof String[])
        template.add("elsePart", (String[]) elsePartStr);
      else
        template.add("elsePart", (String) elsePartStr);
    }

    return (T) template.render();
  }
  //====================================================================================
  // Import
  //====================================================================================
  /**
   * After a conversation with Dr. Fred Barnes and seeing how the nocc compiler does it
   * it turns out we can call functions normally and we just have to pass in the workspace
   * as a parameter to our function. For simplicity it will be the first. We then just
   * have to account for the size of the function as it must fit in the allocated stack
   * of the process that called it.

   * Notice that there is no error checking done by the C code but that's okay as we
   * have done the type checking through the ProcessJ compiler.

   * Returns are done as an extra parameter to the function. We create a compound block and
   * in this compound block we declare a new varaible called returnValue, we are guaranteed
   * this won't class with other variables as it is inside a new scope. We then pass it by
   * reference where the value will be changed inside the function and the function will
   * return nothing! TODO: Why don't we return the argument normally?
   */
  public T visitInvocation(Invocation in){
    //TODO: things go horribly wrong when doing something like f(g())...
    //This could probably be fixed by using a temporary variable.
    Log.log(in.line + ": Visiting Invocation (" + in.procedureName().getname() + ")");

    String functionName = in.procedureName().getname();

    //Print statements are treated differently. TODO: In the future this will change.
    if(functionName.equals("println"))
      return (T) createPrintFunction(in);

    Sequence<Expression> params = in.params();

    //Get out the type belonging to this function so we know if there is a return value!
    //TODO This causes NPE.
    //String returnType = in.targetProc.returnType().typeName();
    //Boolean hasReturn = returnType.equals("void");
    //TODO: Finish implemeting type returning. Really tough right now since the type checker
    //doesn't select the proper function.
    Boolean hasReturn = false;
    String correctTemplate = (!hasReturn) ? "InvocationNoReturn" : "InvocationWihReturn";

    //Array list for ProcParams for this invocation.
    String[] paramArray = (String[]) params.visit(this);
    ST template = group.getInstanceOf(correctTemplate);

    //Add all our fields to our template!
    template.add("functionName", functionName);
    template.add("workspace", globalWorkspace);
    if(paramArray.length != 0)
      template.add("procParams", paramArray);

    //Handle case with return.
    if(hasReturn == true){
      //TODO once Typechecker works change to comemented out line.
      ;//template.add("returnType", returnType);
    }

    return (T) template.render();
  }
  //====================================================================================
  // LocalDecl
  public T visitLocalDecl(LocalDecl ld){
    Log.log(ld.line + ": Visting LocalDecl (" + ld.type().typeName() + " " + ld.var().name().getname() + ")");
    //TODO: isConstant ??
    ST template = group.getInstanceOf("LocalDecl");
    String var = (String) ld.var().visit(this);
    String typeString= (String) ld.type().visit(this);

    //Channels require an initialization and we treat the type different when we are
    //inside a local declr.
    if(ld.type().isChannelType() == true) {
      template.add("channelPart", createChannelInit(var));
      template.add("type", "Channel");
    }
    else if (ld.type().isArrayType() == true) {
      template.add("type", "ArrayStruct");
    }
    else
      template.add("type", typeString);

    template.add("var", var);
    return (T) template.render();
  }
  //====================================================================================
  // Modifier
  //====================================================================================
  // Name
  public T visitName(Name na){
    Log.log(na.line + ": Visiting a Name");

    return (T) na.getname(); //TODO: Fix lower case 'n';
  }
  //====================================================================================
  // NamedType
  //====================================================================================
  // NameExpr
  public T visitNameExpr(NameExpr ne){
    Log.log(ne.line + ": Visiting NameExpr (" + ne.name().getname() + ")");

    //If we are inside a ParBlock we want to add a *<varName> since we have sent the
    //pointer of this variable. This is done to make sure side effects in statements
    //are applied inside ParBlocks. See @visitParBlock for more information.
    if(this.inParBlock == true)
      return (T) ("(*" + ne.toString() + ")");

    return (T) ne.toString();
  }
  //====================================================================================
  // NewArray

  public T visitNewArray(NewArray ne) {
    Log.log(ne.line + ": Visiting a NewArray!");

    ST template = group.getInstanceOf("NewArray");
    String myType = (String)ne.baseType().visit(this);
    Sequence<Expression> sizeExp = ne.dimsExpr();
    // TODO: Expand to n-dimensional arrays
    String[] sizeString = (String[])sizeExp.visit(this);
    String[] dimAllocations = new String[sizeString.length];

    String numDim = Integer.toString(sizeString.length);

    String allocateString = "1";
    for (int i = 0; i < sizeString.length; i++) {
      ST setDimTemplate = group.getInstanceOf("SetArrayDimensions");
      String size = sizeString[i];
      setDimTemplate.add("name", arrayName);
      setDimTemplate.add("num", Integer.toString(i));
      setDimTemplate.add("expr", size);
      dimAllocations[i] = (String) setDimTemplate.render();
      allocateString += " * " + size;
    }

    template.add("globalWsName", globalWorkspace);
    template.add("type", myType);
    template.add("name", arrayName);
    template.add("numDim", numDim);
    template.add("size", allocateString);
    template.add("dimensionList", dimAllocations);

    return (T) template.render();
  }

  //====================================================================================
  // NewMobile
  //====================================================================================
  // ParamDecl
  public T visitParamDecl(ParamDecl pd){
    //TODO: is constant?
    Log.log(pd.line + ": Visiting a ParamDecl!");

    ST template = group.getInstanceOf("ParamDecl");
    String name = pd.name();
    String type;
    if (pd.type() instanceof ArrayType)
        type = "ArrayStruct";
    else
        type = (String) pd.type().visit(this);

    template.add("name", name);
    template.add("type", type);

    return (T) template.render();
  }
  //====================================================================================
  // ParBlock
  /**
   * This par block wraps every statement inside of it in a function with the right
   * parameters. This is needed as the Par API function can only take in functions.
   * From here we generate the code inside of the function, all variables are passed
   * as pointers to make sure we capture all side effects.
   */
  public T visitParBlock(ParBlock pb){ //TODO: Expressions, f(g());
    Log.log(pb.line + ": Visiting a ParBlock");

    Sequence<Statement> stats = pb.stats();
    //TODO fix this so to make sure it works for nested ParBlocks:
    Boolean prevInParBlock = inParBlock;
    this.inParBlock = true;

    //List containing the variadic parameter part needed for ProcPar().
    ArrayList<String> procParList = new ArrayList();
    //List that will hold all the invocation strings per function.
    ArrayList<String> statList = new ArrayList();
    //Template continually passed to createInvocationPar() so it can be updated as
    //new arguments are added, mainly needed as there is no other simple way of knowing
    //what that function and that workspace where called as in f1, f2, f3, etc.
    ST procParTemplate = group.getInstanceOf("ProcPar");
    //Template holding the actual block like syntax.
    ST parBlockTemplate = group.getInstanceOf("ParBlock");
    //Every function will need to know it's index number for since they share an array.
    int i = 0;

    for(Statement myStat : stats){
      //This set contains all the NameExpr for this statement.
      LinkedList<NameExpr> myNames = new LinkedList();
      //Visit our NameCollector for our current statement to get all the NameExpr's!
      myStat.visit(new NameCollector(myNames));

      //Create function to wrap this ParBlock Statement.
      int functionNumber = incrementEntry(this.parBlockStmtCounts, currentFunction);
      String functionName = currentFunction + "ParBlockStmt" + functionNumber;
      //Create invocations statements.
      statList.add( createInvocationPar(functionName, myNames, procParList, i) );
      //Now create actual function!
      parBlockProcs.add( createParBlockProc(functionName, myStat, myNames) );

      //Add this to our ParBlockPrototypes.
      parBlockPrototypes.add( getSimplePrototypeString(functionName) );
      i++;
    }
    //By now procParList is populated so we may add it to our template.
    procParTemplate.add("paramWorkspaceName", globalWorkspace);
    procParTemplate.add("processNumber", stats.size());
    procParTemplate.add("list", procParList);

    parBlockTemplate.add("wsArrayName", parWsName);
    parBlockTemplate.add("wsArraySize", stats.size());
    parBlockTemplate.add("stringStats", statList);
    parBlockTemplate.add("procPar", procParTemplate.render());

    //Restore previous state, this matters in nested ParBlocks.
    this.inParBlock = prevInParBlock;

    return (T) parBlockTemplate.render();
  }
  //====================================================================================
  // Pragma
  //====================================================================================
  // PrimitiveLiteral
  public T visitPrimitiveLiteral(PrimitiveLiteral li){
    Log.log(li.line + ": Visiting a Primitive Literal");

    return (T) li.getText();
  }
  //====================================================================================
  // PrimitiveType
  public T visitPrimitiveType(PrimitiveType py){
    Log.log(py.line + ": Visiting a Primitive Type");

    String typeString = py.toString();
    //Here we list all the primitive types that don't perfectly translate to C.
    if(py.isStringType() == true)
      typeString = "char*";
    if(py.isTimerType() == true)
      typeString = "Time";
    if(py.isBooleanType() == true)
      typeString = "bool";
    //TODO: add Boolean, barrier, timer.
    return (T) typeString;
  }
  //====================================================================================
  // ProcTypeDecl
  public T visitProcTypeDecl(ProcTypeDecl pd){
    String name = (String) pd.name().visit(this);
    Log.log(pd.line + ": Visiting a Proc Type Decl: " + name);

    ST template = group.getInstanceOf("ProcTypeDecl");
    //TODO: Modifiers?
    Sequence<Modifier> modifiers = pd.modifiers();
    //Set our current function.
    this.currentFunction = name;
    //All functions are declared void and their return value is returned through
    //a function parameter. TODO: Why should it be done this way??
    String returnType = "void";

    //This is the last function of the program make sure to shutdown Worskpace!
    if(name.equals(lastFunction)){
      template.add("last", "");
      inLastFunction = true;
    }

    String[] block = (String[]) pd.body().visit(this);
    String[] formals = (String[]) pd.formalParams().visit(this);

    template.add("returnType", returnType);
    template.add("name", name);
    //We need to check for this to avoid an extra comma...
    if(formals.length != 0)
      template.add("formals", formals);
    template.add("body", block);
    template.add("workspace", globalWorkspace);

    inLastFunction = false;
    return (T) template.render();
  }
  //====================================================================================
  // ProtocolLiteral
  //====================================================================================
  // ProtocolCase
  //====================================================================================
  // ProtocolTypeDecl
  //====================================================================================
  // RecordAccess
  //====================================================================================
  // RecordLiteral
  //====================================================================================
  // RecordMembero
  //====================================================================================
  // RecordTypeDecl
  //====================================================================================
  // ReturnStat
  public T visitReturnStat(ReturnStat rs){
    Log.log(rs.line + ": Visiting a ReturnStat");

    //We are on the function that will be called first by our program. This function must
    //be of type void. In order for us to be able to exit from CCSP program succesfully
    //we must call the Shutdown function, since this function may have multiple return
    //statements throughout it we must switch all the returns to goto's so the function
    //always calls the right function.
    if(inLastFunction == true)
      return (T) "goto shutDownLabel"; //TODO: un-hardcoded it, as well as in grammarTemplates.

    ST template = group.getInstanceOf("ReturnStat");
    Expression expr = rs.expr();
    String exprStr = "";

    //Can return null so we must check for this!
    if(expr != null){
      exprStr = (String) expr.visit(this);
      template.add("expr", exprStr);
    }

    return (T) template.render();
  }
  //====================================================================================
  // Sequence
  public T visitSequence(Sequence se){
    Log.log(se.line + ": Visiting a Sequence");
    String[] returnArray = new String[se.size()];

    //Iterate through all children placing results in array.
    for (int i = 0; i<se.size(); i++)
      if (se.child(i) != null)
        returnArray[i] = (String) se.child(i).visit(this);
      else
        returnArray[i] = null;

    return (T) returnArray;
  }
  //====================================================================================
  // SkipStat
  public T visitSkipStat(SkipStat st){
    Log.log(st.line + ": Visiting a SkipStat");
    String comment = "/*This was a skip statement! Nothing to do here!*/";

    return (T) comment;
  }
  //====================================================================================
  // StopStat
  //====================================================================================
  // SuspendStat
  //====================================================================================
  // SwitchGroup
  public T visitSwitchGroup(SwitchGroup sg){
    Log.log(sg.line + ": Visiting a SwitchGroup");

    ST template = group.getInstanceOf("SwitchGroup");
    String[] labels = (String[]) sg.labels().visit(this);
    String[] stmts = (String[]) sg.statements().visit(this);

    template.add("labels", labels);
    template.add("stmts", stmts);

    return (T) template.render();
  }
  //====================================================================================
  // SwitchLabel
  public T visitSwitchLabel(SwitchLabel sl){
    Log.log(sl.line + ": Visiting a SwitchLabel");

    ST template = group.getInstanceOf("SwitchLabel");
    boolean isDefault = sl.isDefault();

    if(isDefault == false){
      String constExpr = (String) sl.expr().visit(this);
      template.add("constExpr", constExpr);
    }
    else
      template.add("constExpr", "default");

    return (T) template.render();
  }
  //====================================================================================
  // SwitchStat
  public T visitSwitchStat(SwitchStat st){
    Log.log(st.line + ": Visiting a SwitchStat");

    ST template = group.getInstanceOf("SwitchStat");
    String expr = (String) st.expr().visit(this);
    String[] switchGroups = (String[]) st.switchBlocks().visit(this);

    template.add("expr", expr);
    template.add("switchGroups", switchGroups);

    return (T) template.render();
  }
  //====================================================================================
  // SyncStat
  //====================================================================================
  // Ternary
  public T visitTernary(Ternary te){
    Log.log(te.line + ": Visiting a Ternary");

    ST template = group.getInstanceOf("Ternary");
    String expr = (String) te.expr().visit(this);
    String trueBranch = (String) te.trueBranch().visit(this);
    String falseBranch = (String) te.falseBranch().visit(this);

    template.add("expr", expr);
    template.add("trueBranch", trueBranch);
    template.add("falseBranch", falseBranch);

    return (T) template.render();
  }
  //====================================================================================
  // TimeoutStat
  //====================================================================================
  // UnaryPostExpr
  public T visitUnaryPostExpr(UnaryPostExpr up){
    Log.log(up.line + ": Visiting a UnaryPostExpr");

    ST template = group.getInstanceOf("UnaryPostExpr");
    String expr = (String) up.expr().visit(this);
    String op = up.opString();

    template.add("expr", expr);
    template.add("op", op);
    return (T) template.render();
  }
  //====================================================================================
  // UnaryPreExpr
  public T visitUnaryPreExpr(UnaryPreExpr up){
    Log.log(up.line + ": Visiting a UnaryPreExpr");

    ST template = group.getInstanceOf("UnaryPreExpr");
    String expr = (String) up.expr().visit(this);
    String op = up.opString();

    template.add("expr", expr);
    template.add("op", op);

    return (T) template.render();
  }
  //====================================================================================
  // Var
  public T visitVar(Var va){
    Log.log(va.line + ": Visiting a Var ("+va.name().getname()+").");

    ST template = group.getInstanceOf("Var");
    String name = (String) va.name().visit(this);
    String exprStr = "";
    Expression expr = va.init();

    template.add("name", name);

    //Expr may be null if the variable is not intialized to anything!
    if(expr != null){
      exprStr = (String) expr.visit(this);
      template.add("init", exprStr);
    }

    return (T) template.render();
  }
  //====================================================================================
  // WhileStat
  public T visitWhileStat(WhileStat ws){
    Log.log(ws.line + ": Visiting a WhileStat");

    ST template = group.getInstanceOf("WhileStat");
    String expr = (String) ws.expr().visit(this);
    Object stats = ws.stat().visit(this);

    template.add("expr", expr);

    //May be one element or multiple.
    if(stats instanceof String[])
      template.add("stat", (String[]) stats);
    else
      template.add("stat", (String) stats);

    return (T) template.render();
  }
  //====================================================================================
  /**
   * Auxillary function, given a protocol it will create the appropriate protoype needed
   * by the equivalent c program. This is used to create all the function protoypes
   * that need to be declared at the top.
   */
  private String getPrototypeString(ProcTypeDecl procedure){
    ST template = group.getInstanceOf("Prototype");
    String name = procedure.name().getname();
    String[] formals = (String[]) procedure.formalParams().visit(this);

    template.add("name", name);
    template.add("workspace", globalWorkspace);
    if(formals.length != 0)
      template.add("formals", formals);

    return template.render();
  }
  //====================================================================================
  /**
   * Auxillary function, given a name it will create the appropriate protoype needed
   * by the equivalent c program. This is used to create all the function protoypes
   * created from a ParBlock.
   * void <name>(Workspace <globalWsName>)
   * @param name: Name of function to create.
   * @return string of our function.
   */
  private String getSimplePrototypeString(String name){
    ST template = group.getInstanceOf("Prototype");

    template.add("name", name);
    template.add("workspace", this.globalWorkspace);

    return template.render();
  }
  //====================================================================================
  /**
   * We treat the printing function a bit different since we will be calling printf from
   * an externCall. So we need to create the appropriate final string to pass to it.
   * This function is only called from visitInvocation().
   * The string we create should look something like this:
   * ExternalCallN(fprintf, 4, stderr, "GuppyStringAssign: src=%p *dst=%p\n", src, *dst);
   */
  private String createPrintFunction(Invocation in){
    ST template = group.getInstanceOf("ExternPrint");
    //println() is a function of only one argument.
    Expression expr = in.params().child(0);
    //The overall type of  expr is a string but may have other types that are
    //concatenated.
    LinkedList<String> names = new LinkedList();
    String printfStr = makePrintfStr(expr, names);
    int size = names.size();

    if(names.size() != 0)
      template.add("argumentList", names);
    template.add("argumentCount", size + 1);
    //Escape character the escape character ;)
    template.add("string", printfStr + "\\n");

    return template.render();
  }
  //====================================================================================
  /**
   * Recursively visits the expression of the println invocation creating an equivalent
   * printf string which will be returned. The passed list will be populated with the
   * equivalent argument for every %d, %f, and %s.
   * TODO: This will break if you give it something like:
   * "cat" + (2 + 2) + "cat";
   * We have to figure out how to fix that.
   */
  private String makePrintfStr(Expression expr, LinkedList lst){
    //If Name Expression we return %s and append name to list.
    if(expr instanceof NameExpr){
      NameExpr ne = (NameExpr)expr;
      Type myType = ne.type;
      String name = ne.toString();
      lst.add(name);

      //Go through the possible types picking the format symbol.
      if(myType.isStringType() == true)
        return "%s";
      if(myType.isFloatType() == true || myType.isDoubleType() == true)
        return "%f";
      if(myType.isIntegerType() == true)
        return "%d";
      if(myType.isLongType() == true)
        return "%lu";
      if(myType.isBooleanType() == true)
        return "%d";

      Error.error("Type for this name expression in println() not implemented yet...");
      return null;
    }

    //Return our primitive literal as text!
    if(expr instanceof PrimitiveLiteral){
      PrimitiveLiteral pl = (PrimitiveLiteral)expr;
      String content = pl.getText();

      //If it's a string then we get rid of the " " on the literal.
      if(pl.getKind() == PrimitiveLiteral.StringKind)
        return content.substring(1, content.length() - 1);

      //Otherwise we just return it as is :)
      return content;
    }

    //Else it's a binary expression recurse down.
    if(expr instanceof BinaryExpr){
      BinaryExpr be = (BinaryExpr)expr;
      return makePrintfStr(be.left(), lst) + makePrintfStr(be.right(), lst);
    }

    //Else error for now...
    Error.error("Expression type %s println() function not implemented yet...");
    return null;
  }
  //====================================================================================
  /**
   * Given a string it will write to the file as the final output of the compiler.
   * TODO: Should probably figure out a way to let user specify name of output file.
   * as of now it always writes to "codeGenerated.c"
   */
  private void writeToFile(String finalOutput){
    Writer writer = null;

    try{
      FileOutputStream fos = new FileOutputStream("codeGenerated.c");
      writer = new BufferedWriter(new OutputStreamWriter(fos, "utf-8"));
      writer.write(finalOutput);
    } catch (IOException ex){
      Log.log("IOException: Could not write to file for some reason :/");
    } finally {
      try {writer.close();} catch(Exception ex){Log.log("Could not close file handle!");}
    }

    return;
  }
  //====================================================================================
  /**
   * Given a hashtable of and the function name it will increment the counter that is the
   * value of that hashtable at for that function name and returns the value of the entry.
   * if the entry was empty it will add it to our table with a value of 1. */
  private int incrementEntry(Hashtable<String, Integer> table, String functionName){
    int returnInt;
    //If we have the key increment the value at that spot.
    if(table.containsKey(functionName) == true){
      Integer integer = table.get(functionName) + 1;
      table.put(functionName, integer);
      returnInt = integer;
    }
    else{
      table.put(functionName, 1);
      returnInt = 1;
    }

    return returnInt;
  }
  //====================================================================================
  /**
   * This is only used for paramater passing to functions inside ParBlocks.
   * Given a Sequence<Expression> holding the parameters for a function and the name of
   * our word, it will return the parameters as strings in the following format:
   * ProcParam(globalWsName, parWs[i], 0, &a);
   * ProcParam(globalWsName, parWs[i], 1, &b);
   * Where a and b are the paramters that are acutally needed, wordPointer is the name
   * of the "global" Workspace, parWsis the name of that function's workspace and the
   * 0 and 1 are parameter number.
   * @param params: Our Parameters to pass in.
   * @param index: for our wsName.
   * @return list of our ProcParam statements.
   */
  private ArrayList<String> createParametersPar(Sequence<Expression> params, int index){
    Log.log("   Creating parameters for ParBlock Statement!");
    ArrayList<String> paramList = new ArrayList();

    for(int i = 0; i < params.size(); i++){
      ST template = group.getInstanceOf("ProcParam");
      template.add("globalWsName", globalWorkspace);
      template.add("parWsName", parWsName);
      template.add("index", index);
      template.add("paramNumber", i);

      //Visit the ith parameters and turn into into an appropriate string.
      Expression paramExpr = params.getElementN(i);
      String paramAsString = (String) paramExpr.visit(this);

      //For ParBlocks we always want to pass the arguments as pointers.
      template.add("param", "&" + paramAsString);
      //Create string and add to our list.
      paramList.add(template.render());
    }

    return paramList;
  }
  //====================================================================================
  /**
   * Given a sequence of formal parameters, it will create string in the form of:
   *   Channel* intOut = ProcGetParam(wordPointer, 0, Channel*);
   * where the type always matches, inOut is the name of this variable.
   * This is needed as once inside functions parameters need to be fetched from
   * CCSP as they were not passed in through the parameters.
   * We need the Booelan forParBlock as it is not enough to use a global as we
   * might have normal method invocations inside of ParBlocks.
   * @param formalParams: Sequence of parameters to create statements for.
   * @param forParBlock: is this for a invocation of a ParBlock statement?
   * @return list of statements.
   */
  private ArrayList<String> createProcGetParams(Sequence<ParamDecl> formalParams,
                                                Boolean forParBlock){
    Log.log("   Creating ProcGetParams(...) for Invocation.");
    ArrayList<String> paramList = new ArrayList();

    for(int i = 0; i < formalParams.size(); i++){
      //In here since we want template reset for every ParmDecl.
      ST template = group.getInstanceOf("ProcGetParam");
      template.add("globalWsName", globalWorkspace);
      template.add("number", i);

      //Get the information from the formal paramters.
      ParamDecl param = formalParams.getElementN(i);
      String typeString = (String) param.type().visit(this);
      String name = param.name();

      //If this is for a ParBlock it was actually a pointer:
      if(forParBlock == true)
        typeString += "*";

      template.add("type", typeString);
      template.add("name", name);
      //Create string and add to our list.
      paramList.add(template.render());
    }

    return paramList;
  }
  //====================================================================================
  /**
   * This function creates the necessary workspace sizes and passes the parameters to
   * the CCSP API so we can eventually run ProcPar(...) and run our stuff in parallel.
   * See InvocationPar in grammarTemplates.stg for information of what it looks like.
   * @param functionName: The function to be called in parallel.
   * @param myNames: Array of Expressions representing the parameters to our function.
   * @param procParList: The final like looks like ProcPar(...) so this list holds
   * what's insdie the function. So everytime this function is called we append
   * the workspace and the function call to this list.
   * @param index: Our ParBlock uses a array of workspaces ws[i] so this is the index
   * for this statement in the par block.
   * @return: The setup and parameter passing for this specific statement, and
   * we append necessary information to procParList.
   */
  String createInvocationPar(String functionName, LinkedList<NameExpr> myNames,
                             ArrayList<String> procParList, int index){
    //This template sets up the main code needed for any invocation to run.
    ST template = group.getInstanceOf("InvocationPar");

    //No need for fcounts here as we know every name is guarandteed to be unique, since
    //multiple statements will each get their own number.
    String wordName = "word" + functionName;

    //Turn our set into a Sequece so we can pass it to our createParameters function!
    Sequence<Expression> params = new Sequence();
    for(NameExpr ne : myNames)
      params.append(ne);

    //This is needed as we are currently trying to pass in the parameters for our
    //invocation from a ParBlock, but since we are in a ParBlock NameExpr will try
    //appending a "*" to our statements. So we tell it not to.
    this.inParBlock = false;
    //Array list for ProcParams for this invocation.
    ArrayList<String> procParams = createParametersPar(params, index);
    this.inParBlock = true;
    int stackSize = getSizeOfFunction(sizePerFunction, functionName);
    //Divide by four as we want the size in words not bytes.
    stackSize = (int) Math.ceil(stackSize / 4.0);

    //Add all our fields to our template!
    template.add("wordName", wordName);
    template.add("paramNumber", myNames.size());
    template.add("stackSize", stackSize);
    template.add("parWs", parWsName);
    template.add("index", index);
    template.add("globalWsName", globalWorkspace);
    template.add("procParams", procParams);

    //Update our ProcPar
    procParList.add(String.format("%s[%d]", parWsName, index));
    procParList.add(functionName);

    return template.render();
  }
  //====================================================================================
  /**
   * Given the results of the var.visit(this) from the visitLocalDecl it will return
   * the channelInit string needed if the visited type was a Channel declaration.
   */
  String createChannelInit(String var){
    ST template = group.getInstanceOf("ChanInit");
      template.add("globalWsName", globalWorkspace);
      template.add("channelName", var);

      return template.render();
  }
  //====================================================================================
  /**
   * Check if given AltCase is a timeout.
   * @param altCase: AltCase to check.
   * @return was this AltCase a timer?
   */
  boolean caseIsTimeout(AltCase altCase){
    Statement stmt = altCase.guard().guard();

    if(stmt instanceof TimeoutStat)
      return true;

    return false;
  }
  //====================================================================================
  /**
   * Check if given AltCase is a Skip.
   * @param altCase: AltCase to check.
   * @return was this AltCase a Skip?
   */
  boolean caseIsSkip(AltCase altCase){
    Statement stmt = altCase.guard().guard();

    if(stmt instanceof SkipStat)
      return true;

    return false;
  }
  //====================================================================================
  /**
   * Check if given AltCase is a Skip.
   * @param altCase: AltCase to check.
   * @return was this AltCase a Skip?
   */
  boolean caseIsChannel(AltCase altCase){
    Statement stmt = altCase.guard().guard();

    if(stmt instanceof ExprStat)
      return true;

    return false;
  }
  //====================================================================================
  /**
   * This function creates an array of {"AltEnableChannel(...)", ... } or
   * {"AltDisableChannel(...)", ... } based on the boolean passed in using templates.
   * @param altCaseList: Sequence of AltCase to create the strings for.
   * @param enable: Whether to create Enables or Disables.
   * @return : our array of strings.
   */
  String[] createEnableDisable(Sequence<AltCase> altCaseList, boolean enable){
    /*These strings decide which template we grab, either the disable or the enable one.*/
    String altTimeoutStr = "AltEnableTimeout";
    String altChannelStr = "AltEnableChannel";
    String altSkipStr = "AltEnableSkip";

    if(enable == false){
      altTimeoutStr = "AltDisableTimeout";
      altChannelStr = "AltDisableChannel";
      altSkipStr = "AltDisableSkip";
    }

    int count = altCaseList.size();
    String[] listOfEnableDisable = new String[count];

    //Iterate over our children making their statements.
    for(int i = 0; i < count; i++){
      AltCase altCase = altCaseList.getElementN(i);

      if( caseIsTimeout(altCase) == true ){
        ST altTimeoutT = group.getInstanceOf(altTimeoutStr);
        //Get expression from the Timeout:
        TimeoutStat time = (TimeoutStat) altCase.guard().guard();
        String myExprStr = (String) time.delay().visit(this);
        String name = (String) altCase.guard().visit(this);

        altTimeoutT.add("globalWsName", globalWorkspace);
        altTimeoutT.add("number", i);
        altTimeoutT.add("name", name);
        //What we actually do, is we use our time variable to hold the time and then we
        //pass this number in as this is needed by the CCSP API. Therefore this is
        //acutally a compound statement. See the grammarTemplate.stg file for details...
        if(enable == true)
          altTimeoutT.add("time", myExprStr);

        listOfEnableDisable[i] = altTimeoutT.render();
      }
      if( caseIsChannel(altCase) == true ){
        ST altChannelT = group.getInstanceOf(altChannelStr);
        String name = (String) altCase.guard().visit(this);

        altChannelT.add("globalWsName", globalWorkspace);
        altChannelT.add("number", i);
        altChannelT.add("name", name);

        listOfEnableDisable[i] = altChannelT.render();
      }
      if( caseIsSkip(altCase) == true ){
        ST altSkipT = group.getInstanceOf(altSkipStr);

        altSkipT.add("globalWsName", globalWorkspace);
        altSkipT.add("number", i);
        listOfEnableDisable[i] = altSkipT.render();
      }

    }

    return listOfEnableDisable;
  }
  //====================================================================================
  /**
   * Given the function name, a statement, and a list of names which we will turn into
   * our parameter list in that order, it will create the proper statement wrapped in
   * a function for the CCSP API to use.
   * @param functionName: name to call our function.
   * @param myStat: Single statement to run in parallel.
   * @param myNames: LinkedList of elements which are our parameters.
   * @return: String with our entire Proc.
   */
  String createParBlockProc(String functionName, Statement myStat,
                            LinkedList<NameExpr> myNames){
    Log.log("   Creating Par Block Proc for Statement named: " + functionName);
    ST template = group.getInstanceOf("ParBlockProc");
    //We will create a sequence of ParamDecl so we can use our already existing
    //createProcGetParams() function.
    Sequence<ParamDecl> paramDeclList = new Sequence();

    for(NameExpr myNameExpr : myNames)
      paramDeclList.append(new ParamDecl(myNameExpr.type, myNameExpr.name(), false));

    //Generate strings:
    String stringStat = (String) myStat.visit(this);
    ArrayList<String> getParameters = createProcGetParams(paramDeclList, true);

    template.add("name", functionName);
    template.add("body", stringStat);
    template.add("paramWorkspaceName", globalWorkspace);
    template.add("getParameters", getParameters);

    return template.render();
  }
  //====================================================================================
  /**
   * Given the hashtable containing the size of each function in bytes, it will look up
   * function name. If the table is null (as it would be on the first pass) return 0, this
   * needs to be zero as the su table takes this numer into account as it is used as the
   * size of a stack allocated array. Else return the size for this function.
   * @param table: table of function to size mappings.
   * @functionName: name to look up.
   */
  int getSizeOfFunction(Hashtable<String, Integer> table, String functionName){
    if(table == null)
      return 0;

    if(table.containsKey(functionName) == false){
      String error = "Function: " + functionName + " not in the sizePerFunction table!"
        +  " This is a bug in the compiler, this should not happen.";
      Error.error(error);
    }

    return table.get(functionName);
  }
  //====================================================================================
  /**
   * Given a string of Java data type, it returns a string of the equivalent C data type.
   * This is for compiler time casting of arrays. TODO expand to all data types that need conversion
   * @param typeName: the string name of the java type
   * @return: String of the C data type
   */
  String getCDataType(String javaType) {
    return javaType.replaceAll("\\[\\]", "") + "*";
  }
}
