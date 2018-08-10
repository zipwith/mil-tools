/*
    Copyright 2018 Mark P Jones, Portland State University

    This file is part of mil-tools.

    mil-tools is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    mil-tools is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with mil-tools.  If not, see <https://www.gnu.org/licenses/>.
*/
package mil;

import compiler.*;
import compiler.BuiltinPosition;
import compiler.Failure;
import compiler.Handler;
import compiler.Position;
import core.*;
import java.io.PrintWriter;

public class Block extends Defn {

  private String id;

  private Temp[] params;

  private Code code;

  /** Default constructor. */
  public Block(Position pos, String id, Temp[] params, Code code) {
    super(pos);
    this.id = id;
    this.params = params;
    this.code = code;
  }

  private static int count = 0;

  public Block(Position pos, Temp[] params, Code code) {
    this(pos, "b" + count++, params, code);
  }

  public Block(Position pos, Code code) {
    this(pos, (Temp[]) null, code);
  }

  /**
   * Set the code field for this block; this is intended to be used in situations where we are
   * generating code for recursively defined blocks whose Code cannot be constucted until the Block
   * itself has been defined.
   */
  public void setCode(Code code) {
    this.code = code;
  }

  private BlockType declared;

  private BlockType defining;

  /** Get the declared type, or null if no type has been set. */
  public BlockType getDeclared() {
    return declared;
  }

  /** Set the declared type. */
  public void setDeclared(BlockType declared) {
    this.declared = declared;
  }

  /** Return the identifier that is associated with this definition. */
  public String getId() {
    return id;
  }

  public String toString() {
    return id;
  }

  /** Find the list of Defns that this Defn depends on. */
  public Defns dependencies() {
    return code.dependencies(null);
  }

  String dotAttrs() {
    return "style=filled, fillcolor=lightblue";
  }

  void displayDefn(PrintWriter out) {
    if (declared != null) {
      out.println(id + " :: " + declared);
    }

    Call.dump(out, id, "[", params, "]");
    out.println(" =");
    if (code == null) {
      Code.indent(out);
      out.println("null");
    } else {
      code.dump(out);
    }
  }

  BlockType instantiate() {
    return (declared != null) ? declared.instantiate() : defining;
  }

  /**
   * Set the initial type for this definition by instantiating the declared type, if present, or
   * using type variables to create a suitable skeleton. Also sets the types of bound variables.
   */
  void setInitialType() throws Failure {
    // Rename to ensure that every block has a distinct set of temporaries:
    Temp[] old = params;
    params = Temp.makeTemps(old.length);
    code = code.forceApply(TempSubst.extend(old, params, null));

    // Set initial types for temporaries:
    Type dom = Type.tuple(Type.freshTypes(params));
    if (declared == null) {
      Type rng = new TVar(Tyvar.tuple);
      defining = new BlockType(dom, rng);
    } else {
      defining = declared.instantiate();
      defining.domUnifiesWith(pos, dom);
    }
  }

  /**
   * Type check the body of this definition, but reporting rather than throwing' an exception error
   * if the given handler is not null.
   */
  void checkBody(Handler handler) throws Failure {
    try {
      checkBody(pos);
    } catch (Failure f) {
      // We can recover from a type error in this definition (at least for long enough to type
      // check other definitions) if the types are all declared (and there is a handler).
      if (allTypesDeclared() && handler != null) {
        handler.report(f); // Of course, we still need to report the failure
        defining = null; // Mark this definition as having failed to check
      } else {
        throw f;
      }
    }
  }

  /** Type check the body of this definition, throwing an exception if there is an error. */
  void checkBody(Position pos) throws Failure {
    code.inferType(pos).unify(pos, defining.rngType());
  }

  /** Check that there are declared types for all of the items defined here. */
  boolean allTypesDeclared() {
    return declared != null;
  }

  /** Lists the generic type variables for this definition. */
  protected TVar[] generics = TVar.noTVars;

  /** Produce a printable description of the generic variables for this definition. */
  public String showGenerics() {
    return TVar.show(generics);
  }

  void generalizeType(Handler handler) throws Failure {
    // !   debug.Log.println("Generalizing definition for: " + getId());
    if (defining != null) {
      TVars gens = defining.tvars();
      generics = TVar.generics(gens, null);
      // !     debug.Log.println("generics: " + showGenerics());
      BlockType inferred = defining.generalize(generics);
      debug.Log.println("Inferred " + id + " :: " + inferred);
      if (declared == null) {
        declared = inferred;
      } else if (!declared.alphaEquiv(inferred)) {
        throw new Failure(
            "Declared type \""
                + declared
                + "\" for \""
                + id
                + "\" is more general than inferred type \""
                + inferred
                + "\"");
      }
      findAmbigTVars(handler, gens); // search for ambiguous type variables ...
    }
  }

  void findAmbigTVars(Handler handler, TVars gens) {
    String extras = TVars.listAmbigTVars(code.tvars(gens), gens);
    if (extras != null) {
      // TODO: do we need to apply a skeleton() method to defining?
      // handler.report(new Failure(pos,  ...));
      debug.Log.println( // TODO: replace this call with the handler above ...
          "Block \""
              + id
              + "\" used at type "
              + defining
              + " with ambiguous type variables "
              + extras);
    }
  }

  /** First pass code generation: produce code for top-level definitions. */
  void generateMain(Handler handler, MachineBuilder builder) {
    /* skip these definitions on first pass */
  }

  /** Second pass code generation: produce code for block and closure definitions. */
  void generateFunctions(MachineBuilder builder) {
    builder.resetFrame();
    builder.setAddr(this, builder.getNextAddr());
    builder.extend(params, 0);
    code.generateCode(builder, params.length);
  }

  /** Stores the list of blocks that have been derived from this block. */
  private Blocks derived = null;

  /**
   * Derive a new version of this block using a code sequence that applies its final result to a
   * specifed argument value instead of returning that value (presumably a closure) to the calling
   * code where it is immediately entered and then discarded. The parameter m determines the number
   * of additional arguments that will eventually be passed when the closure is entered.
   */
  public Block deriveWithEnter(int m) {
    // Look to see if we have already derived a suitable version of this block:
    for (Blocks bs = derived; bs != null; bs = bs.next) {
      if (bs.head instanceof BlockWithEnter) {
        return bs.head;
      }
    }

    // Generate a fresh block; we have to make sure that the new block is added to the derived list
    // before we begin
    // generating code to ensure that we do not end up with multiple (or potentially, infinitely
    // many copies of the
    // new block).
    Temp[] iargs = Temp.makeTemps(m); // temps for extra args
    Temp[] nps = Temp.append(params, iargs); // added to original params
    Block b = new BlockWithEnter(pos, nps, null);
    derived = new Blocks(b, derived);
    b.code = code.deriveWithEnter(iargs);
    // !System.out.println("Derived block is:");
    // !b.displayDefn();
    return b;
  }

  /**
   * Derive a new version of this block using a code sequence that passes its final result to a
   * specifed continuation function instead of returning that value to the calling code.
   */
  public Block deriveWithCont() {
    // Look to see if we have already derived a suitable version of this block:
    for (Blocks bs = derived; bs != null; bs = bs.next) {
      if (bs.head instanceof BlockWithCont) {
        return bs.head;
      }
    }

    // Generate a fresh block; we have to make sure that the new block is added to the derived list
    // before we
    // begin generating code to ensure that we do not end up with multiple (or potentially,
    // infinitely many
    // copies of the new block).

    Temp arg = new Temp(); // represents continuation
    int l = params.length; // extend params with arg
    Temp[] nps = new Temp[l + 1];
    nps[l] = arg;
    for (int i = 0; i < l; i++) {
      nps[i] = params[i];
    }
    Block b = new BlockWithCont(pos, nps, null);
    derived = new Blocks(b, derived);
    b.code = code.deriveWithCont(arg);
    // !System.out.println("Derived block is:");
    // !b.displayDefn();
    return b;
  }

  /**
   * Heuristic to determine if this block is a good candidate for the casesOn(). TODO: investigate
   * better functions for finding candidates!
   */
  boolean contCand() {
    // Warning: an scc for this block may not be known if this block has only just been created.
    DefnSCC scc = getScc();
    return code.isDone() == null
        // && !(this instanceof BlockWithKnownCons)
        && scc != null
        && scc.contCand();
  }

  public Block deriveWithKnownCons(Call[] calls) {
    // !System.out.println("Looking for derive with Known Cons ");
    // !Call.dump(calls);
    // !System.out.println(" for the Block");
    // !this.dump();
    // !System.out.println();

    // Do not create a specialized version of a simple block (i.e., a block that contains only a
    // single Done):
    //    if (code instanceof Done) {
    // System.out.println("Will not specialize this block: code is a single tail");
    //      return null;
    //  }
    // !code.dump();

    // TODO: this test is disabled, which results in more aggressive inlining that, so
    // far, appears to be a good thing :-)  Consider removing this test completely ... ?
    //  if (this instanceof BlockWithKnownCons) {
    // !System.out.println("Will not specialize this block: starting point is a derived block");
    //      return null;
    //  }

    // !if (calls!=null) return null; // disable deriveWithKnownCons

    // Look to see if we have already derived a suitable version of this block:
    for (Blocks bs = derived; bs != null; bs = bs.next) {
      if (bs.head.hasKnownCons(calls)) {
        // !System.out.println("Found a previous occurrence");
        // Return pointer to previous occurrence, or decline the request to specialize
        // the block if the original block already has the requested allocator pattern.
        return (this == bs.head) ? null : bs.head;
      }
    }

    // !System.out.println("Generating a new block");
    // Generate a fresh block; unlike the case for trailing Enter, we're only going to create one
    // block here
    // whose code is the same as the original block except that it adds a group of one or more
    // initializers.
    // Our first step is to initialize the block:
    Block b = new BlockWithKnownCons(pos, null, calls);
    derived = new Blocks(b, derived);

    // Next we pick temporary variables for new parameters:
    Temp[][] tss = Call.makeTempsFor(calls);

    // Combine old parameters and new temporaries to make new parameter list:
    if (tss == null) {
      b.params = params; // TODO: safe to reuse params, or should we make a copy?
      b.derived = new Blocks(b, b.derived);
    } else {
      b.params = mergeParams(tss, params);
    }

    // Fill in the code for the new block by prepending some initializers:
    b.code = addInitializers(calls, params, tss, code.copy());

    // !System.out.println("New deriveWithKnownCons block:");
    // !b.displayDefn();
    return b;
  }

  boolean hasKnownCons(Call[] calls) {
    return false;
  }

  public Block deriveWithDuplicateArgs(int[] dups) {
    if (dups == null) {
      debug.Internal.error("null argument for deriveWithDuplicateArgs");
    }
    // !System.out.print("Looking for derive with Duplicate Arguments ");
    // !printDups(dups);
    // !System.out.println(" for the Block");
    // !this.dump();
    // !System.out.println();

    // Look to see if we have already derived a suitable version of this block:
    for (Blocks bs = derived; bs != null; bs = bs.next) {
      if (bs.head.hasDuplicateArgs(dups)) {
        // !System.out.println("Found a previous occurrence");
        // Return pointer to previous occurrence:
        return bs.head;
      }
    }

    // !System.out.println("Generating a new block");
    // Count the number of duplicate params to remove so that we can determine
    // how many formal parameters the derived block should have.
    int numDups = 0;
    for (int i = 0; i < dups.length; i++) {
      if (dups[i] != 0) {
        numDups++;
      }
    }
    if (numDups == 0) {
      debug.Internal.error("no duplicates found for deriveWithDuplicateArgs");
    } else if (numDups >= params.length) {
      debug.Internal.error("too many duplicates in deriveWithDuplicateArgs");
    }

    // Create a new list of params (a subsequence of the original list) and build a substitution to
    // describe
    // what will happen to params that are eliminated as duplicates.
    Temp[] nps = Temp.makeTemps(params.length - numDups);
    int j = 0;
    TempSubst s = null;
    for (int i = 0; i < dups.length; i++) {
      if (dups[i] == 0) { // Not a duplicated parameter:
        s = params[i].mapsTo(nps[j++], s); // - map old to new
      } else { // Duplicated parameter:
        s = params[i].mapsTo(params[dups[i] - 1].apply(s), s); // - map to where original went
      }
    }

    Block b = new BlockWithDuplicateArgs(pos, nps, code.forceApply(s), dups);
    // TODO: should we set a declared type for b if this block has one?
    derived = new Blocks(b, derived);
    // !System.out.println("New deriveWithDuplicateArgs block:");
    // !b.displayDefn();
    return b;
  }

  /**
   * Check to see if this is a derived version of a block with duplicate arguments that matches the
   * given pattern.
   */
  boolean hasDuplicateArgs(int[] dups) {
    return false;
  }

  /**
   * Flag to identify blocks that "do not return". In other words, if the value of this flag for a
   * given block b is true, then we can be sure that (x <- b[args]; c) == b[args] for any
   * appropriate set of arguments args and any valid code sequence c. There are two situations that
   * can cause a block to "not return". The first is when the block enters an infinite loop; such
   * blocks may still be productive (such as the block defined by b[x] = (_ <- print((1)); b[x])),
   * so we cannot assume that they will be eliminated by eliminateLoops(). The second is when the
   * block's code sequence makes a call to a primitive call that does not return.
   */
  private boolean doesntReturn = false;

  /**
   * Return flag, computed by previous dataflow analysis, to indicate if this block does not return.
   */
  boolean doesntReturn() {
    return doesntReturn;
  }

  /**
   * Reset the doesntReturn flag, if there is one, for this definition ahead of a returnAnalysis().
   * For this analysis, we use true as the initial value, reducing it to false if we find a path
   * that allows a block's code to return.
   */
  void resetDoesntReturn() {
    this.doesntReturn = true;
  }

  /**
   * Apply return analysis to this definition, returning true if this results in a change from the
   * previously computed value.
   */
  boolean returnAnalysis() {
    boolean newDoesntReturn = code.doesntReturn();
    if (newDoesntReturn != doesntReturn) {
      doesntReturn = newDoesntReturn;
      return true; // signal that a change was detected
    }
    return false; // no change
  }

  /** Perform pre-inlining cleanup on each Block in this SCC. */
  void cleanup() {
    code = code.cleanup(this);
  }

  boolean detectLoops(Blocks visited) {
    // Check to see if this block calls code for an already visited block:
    if (Blocks.isIn(this, visited) || code.detectLoops(this, visited)) {
      MILProgram.report("detected an infinite loop in block " + getId());
      code = new Done(PrimCall.loop);
      return true;
    }
    return false;
  }

  /** Apply inlining. */
  public void inlining() {
    // !System.out.println("==================================");
    // !System.out.println("Going to try inlining on " + getId());
    // !displayDefn();
    // !System.out.println();
    if (isGotoBlock() == null) { // TODO: consider replacing with code.isDone()
      code = code.inlining(this);
      // !System.out.println("And the result is:");
      // !displayDefn();
      // !System.out.println();
    }
  }

  public static final int INLINE_LINES_LIMIT = 6;

  boolean canPrefixInline(Block src) {
    if (this.getScc() != src.getScc()) { // Restrict to different SCCs
      int n = code.prefixInlineLength(0);
      return n > 0 && (occurs == 1 || n <= INLINE_LINES_LIMIT);
    }
    return false;
  }

  /**
   * Attempt to inline the code for this block onto the front of another block of code. Assumes that
   * the final result computed by this block will be bound to the variables in rs, and that the
   * computation will proceed with the code specified by rest. The src value specifies the block in
   * which the original BlockCall appeared while args specifies the set of arguments that were
   * passed in at that call. A null return indicates that no inlining was performed.
   */
  Code prefixInline(Block src, Atom[] args, Temp[] rs, Code rest) {
    if (canPrefixInline(src)) {
      MILProgram.report(
          "prefixInline succeeded for call to block " + getId() + " from block " + src.getId());
      return code.prefixInline(TempSubst.extend(params, args, null), rs, rest);
    }
    return null;
  }

  /**
   * Attempt to construct an inlined version of the code in this block that can be placed at the end
   * of a Code sequence. Assumes that a BlockCall to this block with the given set of arguments was
   * included in the specified src Block. A null return indicates that no inlining was performed.
   */
  Code suffixInline(Block src, Atom[] args) {
    // !System.out.println("Should we inline:");
    // !displayDefn();
    // !System.out.println();
    // !System.out.println("As part of the block:");
    // !if (src==null) System.out.println("Null block"); else src.displayDefn();
    // !System.out.println("?");
    if (canSuffixInline(src)) {
      // !System.out.println("YES");
      MILProgram.report(
          "suffixInline succeeded for call to block " + getId() + " from block " + src.getId());
      return code.apply(TempSubst.extend(params, args, null));
    }
    // !System.out.println("NO");
    return null;
  }

  /**
   * We allow a block to be inlined if the original call is in a different block, the code for the
   * block ends with a Done, and either there is only one reference to the block in the whole
   * program, or else the length of the code sequence is at most INLINE_LINES_LIMIT lines long.
   */
  boolean canSuffixInline(Block src) {
    if (doesntReturn && getScc().isRecursive()) { // Avoid loopy code that doesn't return
      return false;
    } else if (occurs == 1 || code.isDone() != null) { // Inline single occurrences and trivial
      // !System.out.println("Single occurrence!");
      // !System.out.println("this block:");
      // !this.displayDefn();
      // !System.out.println("src block:");
      // !src.displayDefn();
      // !System.out.println("-=-=-=-=-=-");
      return true; // blocks (safe, as a result of removing loops)
    } else if (!this.guarded(src)) { // Don't inline if not guarded.
      // !System.out.println("Not guarded!");
      return false;
    } else {
      int n = code.suffixInlineLength(0); // Inline code blocks that are short
      // !System.out.println("inline length = " + n);
      return n > 0 && n <= INLINE_LINES_LIMIT;
    }
  }

  /**
   * Determine if, for the purposes of suffix inlining, it is possible to get back to the specified
   * source block via a sequence of tail calls. (i.e., without an If or Case guarding against an
   * infinite loop.)
   */
  boolean guarded(Block src) {
    return (this != src) && (this.getScc() != src.getScc() || code.guarded(src));
  }

  /** Test to see if a call to this block with specific arguments can be replaced with a Call. */
  public Tail inlineTail(Atom[] args) {
    Tail tail = code.isDone();
    if (tail != null) {
      tail = tail.forceApply(TempSubst.extend(params, args, null));
    }
    return tail;
  }

  /**
   * Rewrite a call to a goto Block as a new BlockCall that bypasses the goto. In other words, if
   * b[p1,...] = b'[a1, ...] then we can rewrite a call of the form b[x1,...] as a call to
   * [x1/p1,...]b'[a1,...]. If the block b is not a goto block, or if we call this method on a tail
   * that is not a block call, then a null value will be returned.
   */
  BlockCall bypassGotoBlockCall(Atom[] args) {
    BlockCall bc = this.isGotoBlock();
    if (bc != null) {
      MILProgram.report("elided call to goto block " + getId());
      return bc.forceApplyBlockCall(TempSubst.extend(params, args, null));
    }
    return null;
  }

  /**
   * Test to determine whether this Block is a "goto" block, meaning that: a) its body is an
   * immediate call to another block; and b) either this block has parameters or else the block in
   * its body has no arguments. (A block defined by b[] = b'[a1,...] is considered to be an "entry"
   * block rather than a "goto" block.)
   */
  BlockCall isGotoBlock() {
    return code.isGoto(params.length);
  }

  void liftAllocators() {
    code.liftAllocators();
  }

  /**
   * A bitmap that identifies the used arguments of this definition. The base case, with no used
   * arguments, can be represented by a null array. Otherwise, it will be a non null array, the same
   * length as the list of parameters, with true in positions corresponding to arguments that are
   * known to be used and false in all other positions.
   */
  private boolean[] usedArgs = null;

  /**
   * Counts the total number of used arguments in this definition; this should match the number of
   * true values in the usedArgs array.
   */
  private int numUsedArgs = 0;

  /** Reset the bitmap and count for the used arguments of this definition, where relevant. */
  void clearUsedArgsInfo() {
    usedArgs = null;
    numUsedArgs = 0;
  }

  /**
   * Count the number of unused arguments for this definition using the current unusedArgs
   * information for any other items that it references.
   */
  int countUnusedArgs() {
    return countUnusedArgs(params);
  }

  /**
   * Count the number of unused arguments for this definition. A zero count indicates that all
   * arguments are used.
   */
  int countUnusedArgs(Temp[] dst) {
    int unused = dst.length - numUsedArgs; // count # of unused args
    if (unused > 0) { // skip if no unused args
      usedVars = usedVars(); // find vars used in body
      for (int i = 0; i < dst.length; i++) { // scan argument list
        if (usedArgs == null || !usedArgs[i]) { // skip if already known to be used
          if (dst[i].isIn(usedVars) && !duplicated(i, dst)) {
            if (usedArgs == null) { // initialize usedArgs for first use
              usedArgs = new boolean[dst.length];
            }
            usedArgs[i] = true; // mark this argument as used
            numUsedArgs++; // update counts
            unused--;
          }
        }
      }
    }
    return unused;
  }

  private Temps usedVars;

  /**
   * A utility function that returns true if the variable at position i in the given array also
   * appears in some earlier position in the array. (If this condition applies, then we can mark the
   * later occurrence as unused; there is no need to pass the same variable twice.)
   */
  private static boolean duplicated(int i, Temp[] dst) {
    // Did this variable appear in an earlier position?
    for (int j = 0; j < i; j++) {
      if (dst[j] == dst[i]) {
        return true;
      }
    }
    return false;
  }

  /**
   * Find the list of variables that are used in this definition. Variables that are mentioned in
   * BlockCalls or ClosAllocs are only included if the corresponding flag in usedArgs is set.
   */
  Temps usedVars() {
    return code.usedVars();
  }

  /**
   * Find the list of variables that are used in a call to this definition, taking account of the
   * usedArgs setting so that we only include variables appearing in argument positions that are
   * known to be used.
   */
  Temps usedVars(Atom[] args, Temps vs) {
    if (usedArgs != null) { // ignore this call if no args are used
      for (int i = 0; i < args.length; i++) {
        if (usedArgs[i]) { // ignore this argument if the flag is not set
          vs = args[i].add(vs);
        }
      }
    }
    return vs;
  }

  /**
   * Use information about which and how many argument positions are used to trim down an array of
   * destinations (specifically, the formal parameters of a Block or a ClosureDefn).
   */
  Temp[] removeUnusedTemps(Temp[] dsts) {
    // ! System.out.println("In " + getId() + ": numUsedArgs=" + numUsedArgs + ", dsts.length=" +
    // dsts.length);
    if (numUsedArgs < dsts.length) { // Found some new, unused args
      Temp[] newTemps = new Temp[numUsedArgs];
      int j = 0;
      for (int i = 0; i < dsts.length; i++) {
        if (usedArgs != null && usedArgs[i]) {
          newTemps[j++] = dsts[i];
        } else {
          MILProgram.report("removing unused argument " + dsts[i] + " from " + getId());
        }
      }
      return newTemps;
    }
    return dsts; // No newly discovered unused arguments
  }

  /**
   * Update an argument list by removing unused arguments, or return null if no change is required.
   */
  Atom[] removeUnusedArgs(Atom[] args) {
    if (numUsedArgs < args.length) { // Only rewrite if we have found some new unused arguments
      Atom[] newArgs = new Atom[numUsedArgs];
      int j = 0;
      for (int i = 0; i < args.length; i++) {
        if ((usedArgs != null && usedArgs[i])) {
          newArgs[j++] = args[i];
        }
      }
      return newArgs;
    }
    return null; // The argument list should not be changed
  }

  /** Rewrite this program to remove unused arguments in block calls. */
  void removeUnusedArgs() {
    if (numUsedArgs < params.length) {
      MILProgram.report(
          "Rewrote block "
              + getId()
              + " to eliminate "
              + (params.length - numUsedArgs)
              + " unused parameters");
      params = removeUnusedTemps(params); // remove unused formal parameters
      if (declared != null) {
        declared = declared.removeArgs(numUsedArgs, usedArgs);
      }
    }
    code = code.removeUnusedArgs(); // update calls in code sequence
  }

  public void flow() {
    code = code.flow(null /*facts*/, null /*substitution*/);
    code.liveness();
  }

  /**
   * A simple test for MIL code fragments that return a known FlagConst, returning either the
   * constant or null.
   */
  FlagConst returnsFlagConst() {
    return code.returnsFlagConst();
  }

  /**
   * Test to determine whether there is a way to short out a Case from a call to this block with the
   * specified arguments, and given the set of facts that have been computed. We start by querying
   * the code in the Block to determine if it starts with a Case; if not, then the optimization will
   * not apply and a null result is returned.
   */
  BlockCall shortCase(Atom[] args, Facts facts) {
    return code.shortCase(params, args, facts);
  }

  /**
   * Compute an integer summary for a fragment of MIL code with the key property that alpha
   * equivalent program fragments have the same summary value.
   */
  int summary() {
    return id.hashCode();
  }

  /** Test to see if two Block values are alpha equivalent. */
  boolean alphaBlock(Block that) {
    // Check for same number of parameters:
    if (this.params.length != that.params.length) {
      return false;
    }

    // Build lists of parameters:
    Temps thisvars = null;
    for (int i = 0; i < this.params.length; i++) {
      thisvars = this.params[i].add(thisvars);
    }
    Temps thatvars = null;
    for (int i = 0; i < that.params.length; i++) {
      thatvars = that.params[i].add(thatvars);
    }

    // Check bodies for alpha equivalence:
    return this.code.alphaCode(thisvars, that.code, thatvars);
  }

  /** Holds the most recently computed summary value for this item. */
  private int summary;

  /**
   * Points to a different block with equivalent code, if one has been identified. A null value
   * indicates that there is no replacement block.
   */
  private Block replaceWith = null;

  Block getReplaceWith() {
    return replaceWith;
  }

  /**
   * Look for a previously summarized version of this block, returning true if a duplicate was
   * found.
   */
  boolean findIn(Blocks[] blocks) {
    summary = code.summary();
    // !System.out.println("Block " + getId() + " has summary " + this.summary);
    int idx = this.summary % blocks.length;
    if (idx < 0) idx += blocks.length;

    for (Blocks bs = blocks[idx]; bs != null; bs = bs.next) {
      if (bs.head.summary == this.summary && bs.head.alphaBlock(this)) {
        // !System.out.println("Matching summaries for " + bs.head.getId() + " and " +
        // this.getId());
        // !bs.head.displayDefn();
        // !this.displayDefn();
        MILProgram.report("Replacing " + this.getId() + " with " + bs.head.getId());
        this.replaceWith = bs.head;
        return true;
      }
      // !System.out.println("Did not match");
    }
    // First sighting of this block, add to the table:
    this.replaceWith = null; // There is no replacement for this block
    blocks[idx] = new Blocks(this, blocks[idx]);
    // TODO: why not just use a standard java.util.HashMap?
    return false;
  }

  /**
   * Compute a summary for this definition (if it is a block or top-level) and then look for a
   * previously encountered item with the same code in the given table. Return true if a duplicate
   * was found.
   */
  boolean summarizeDefns(Blocks[] blocks, TopLevels[] topLevels) {
    return findIn(blocks);
  }

  void eliminateDuplicates() {
    code.eliminateDuplicates();
  }

  Atom[] replaceArgs(Block b, Atom[] args) {
    Atom[] newArgs = new Atom[this.params.length];
    for (int i = 0, j = 0; i < this.params.length; ) {
      newArgs[i++] = args[j++];
    }
    return newArgs;
  }

  void collect() {
    code.collect();
  }

  private Atom[] argVals;

  void clearArgVals() {
    argVals = new Atom[params.length];
  }

  void collect(Atom[] args) {
    if (args.length != argVals.length) {
      debug.Internal.error("Argument length mismatch in collect");
    }
    for (int i = 0; i < args.length; i++) {
      argVals[i] = args[i].update(argVals[i]);
    }
  }

  void checkCollection() {
    for (int i = 0; i < argVals.length; i++) {
      if (argVals[i] != null) {
        Atom known = argVals[i].isKnown();
        if (known != null) {
          MILProgram.report("Argument " + i + " of " + getId() + " is always " + known);
          code = new Bind(params[i], new Return(argVals[i]), code);
        }
      }
    }
  }

  void collect(TypeSet set) {
    if (declared != null) {
      declared = declared.canonBlockType(set);
    }
    if (defining != null) {
      defining = defining.canonBlockType(set);
    }
    Atom.collect(params, set);
    code.collect(set);
  }

  /** Apply constructor function simplifications to this program. */
  void cfunSimplify() {
    code = code.cfunSimplify();
  }

  void printlnSig(PrintWriter out) {
    out.println(id + " :: " + declared);
  }

  /** Test to determine if this is an appropriate definition to match the given type. */
  Block isBlockOfType(BlockType inst) {
    return declared.alphaEquiv(inst) ? this : null;
  }

  Block(Block b) {
    this(b.pos, null);
  }

  /** Fill in the body of this definition as a specialized version of the given block. */
  void specialize(MILSpec spec, Block borig) {
    TVarSubst s = borig.declared.specializingSubst(borig.generics, this.declared);
    debug.Log.println(
        "Block specialize: "
            + borig.getId()
            + " :: "
            + borig.declared
            + "  ~~>  "
            + this.getId()
            + " :: "
            + this.declared
            + ", generics="
            + borig.showGenerics()
            + ", substitution="
            + s);
    this.params = Temp.specialize(s, borig.params);
    SpecEnv env = new SpecEnv(borig.params, this.params, null);
    this.code = borig.code.specializeCode(spec, s, env);
  }

  /**
   * Generate a specialized version of an entry point. This requires a monomorphic definition (to
   * ensure that the required specialization is uniquely determined, and to allow the specialized
   * version to share the same name as the original.
   */
  Defn specializeEntry(MILSpec spec) throws Failure {
    if (declared.isQuantified()) {
      throw new PolymorphicEntrypointFailure("block", this);
    }
    Block b = spec.specializedBlock(this, declared);
    b.id = this.id; // use the same name as in the original program
    return b;
  }

  /** Update all declared types with canonical versions. */
  void canonDeclared(MILSpec spec) {
    declared = declared.canonBlockType(spec);
  }

  /** Rewrite the components of this definition to account for changes in representation. */
  void repTransform(Handler handler, RepTypeSet set) {
    Temp[][] npss = Temp.reps(params); // analyze params
    RepEnv env = Temp.extend(params, npss, null); // environment for params
    params = Temp.repParams(params, npss);
    code = code.repTransform(set, env);
    declared = declared.canonBlockType(set);
  }

  public static Block returnTrue = atomBlock("returnTrue", FlagConst.True);

  public static Block returnFalse = atomBlock("returnFalse", FlagConst.False);

  /**
   * Make a block of the following form that immediately returns the atom a, which could be an
   * IntConst or a Top, but not a Temp (because that would be out of scope). b :: [] >>= [t] b[] =
   * return a
   */
  public static Block atomBlock(String name, Atom a) {
    return new Block(BuiltinPosition.position, name, Temp.noTemps, new Done(new Return(a)));
  }

  /**
   * Perform scope analysis on a definition of this block, creating a new temporary for each of the
   * input parameters and checking that all identifiers used in the given code sequence have a
   * corresponding binding.
   */
  public void inScopeOf(Handler handler, MILEnv milenv, String[] ids, CodeExp cexp) throws Failure {
    Temp[] ps = Temp.makeTemps(ids.length);
    this.params = ps;
    this.code = cexp.inScopeOf(handler, milenv, new TempEnv(ids, ps, null));
  }

  /** Add this exported definition to the specified MIL environment. */
  void addExport(MILEnv exports) {
    exports.addBlock(id, this);
  }

  /**
   * Find the arguments that are needed to enter this definition. The arguments for each block or
   * closure are computed the first time that we visit the definition, but are returned directly for
   * each subsequent call.
   */
  Temp[] addArgs() throws Failure {
    // !     System.out.println("In Block " + getId());
    if (params == null) { // compute formal params on first visit
      params = Temps.toArray(code.addArgs());
    }
    return params;
  }

  /** Returns the LLVM type for value that is returned by a function. */
  llvm.Type retType(TypeMap tm) {
    return declared.retType(tm);
  }

  int numberCalls;

  public int getNumberCalls() {
    return numberCalls;
  }

  /**
   * Reset the counter for the number of non-tail calls to this definition. This is only useful for
   * Blocks: we have to generate an LLVM function for every reachable closure definition anyway, but
   * we only need to do this for Blocks that are either listed as entrypoints or that are accessed
   * via a non-tail call somewhere in the reachable program.
   */
  void resetCallCounts() {
    numberCalls = 0;
  }

  /** Mark this definition as having been called. */
  void called() {
    numberCalls++;
  }

  /** Count the number of non-tail calls to blocks in this abstract syntax fragment. */
  void countCalls() {
    code.countCalls();
  }

  /**
   * Identify the set of blocks that should be included in the function that is generated for this
   * definition. A block call in the tail for a TopLevel is considered a regular call (it will
   * likely be called from the initialization code), but a block call in the tail for a ClosureDefn
   * is considered a genuine tail call. For a Block, we only search for the components of the
   * corresponding function if the block is the target of a call.
   */
  Blocks identifyBlocks() {
    return (numberCalls == 0) ? null : code.identifyBlocks(this, new Blocks(this, null));
  }

  Blocks identifyBlocks(Blocks bs) {
    return code.identifyBlocks(this, bs);
  }

  /**
   * Determine if this block is "small", which is intended to capture the intuition that this block
   * will not do a lot of work before returning. In concrete, albeit somewhat arbitrary terms, we
   * define this to mean that the block is not part of a recursive scc, and that it does at most
   * SMALL_STEPS Bind instructions before reaching a Done.
   */
  boolean isSmall() {
    return !getScc().isRecursive() && code.isSmall(SMALL_STEPS);
  }

  private static final int SMALL_STEPS = 4;

  /** Return a string label that can be used to identify this node. */
  String label() {
    return "func_" + id;
  }

  CFG makeCFG() {
    if (numberCalls == 0) {
      return null;
    } else {
      // The entry point and the first block should use different parameter names (but the number
      // and type of the parameters should be the same):
      Temp[] nparams = new Temp[params.length];
      for (int i = 0; i < params.length; i++) {
        nparams[i] = params[i].newParam();
      }
      BlockCFG cfg = new BlockCFG(this, nparams);
      cfg.initCFG();
      return cfg;
    }
  }

  /** Find the CFG successors for this definition. */
  Label[] findSuccs(CFG cfg, Node src) {
    return code.findSuccs(cfg, src);
  }

  TempSubst mapParams(Atom[] args, TempSubst s) {
    return TempSubst.extend(params, TempSubst.apply(args, s), s);
  }

  llvm.Code toLLVM(TypeMap tm, VarMap vm, TempSubst s, Label[] succs) {
    return code.toLLVM(tm, vm, s, succs);
  }

  Temp[] getParams() {
    return params;
  }

  llvm.Global blockGlobalCalc(TypeMap tm) {
    return new llvm.Global(declared.toLLVM(tm), label());
  }
}
