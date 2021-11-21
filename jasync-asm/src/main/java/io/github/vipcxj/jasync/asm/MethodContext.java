package io.github.vipcxj.jasync.asm;

import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicValue;

import java.util.*;
import java.util.function.BiFunction;

public class MethodContext {
    private final ClassContext classContext;
    private final MethodNode mv;
    private final BranchAnalyzer.Node<BasicValue>[] frames;
    private final Map<LabelNode, LabelNode> cloneLabels;
    private final List<Integer> localsToUpdate;
    private final List<Integer> stacksToUpdate;

    protected final MethodContext parent;
    private final List<MethodContext> children;
    private final AbstractInsnNode[] insnNodes;
    private List<Set<Integer>> loops;



    public MethodContext(ClassContext classContext, MethodNode mv, MethodContext parent) {
        this.classContext = classContext;
        this.mv = mv;
        this.localsToUpdate = new ArrayList<>();
        this.stacksToUpdate = new ArrayList<>();
        this.cloneLabels = new HashMap<>();
        collectLabels();
        BranchAnalyzer analyzer = new BranchAnalyzer();
        try {
            analyzer.analyze(classContext.getName(), mv);
            this.frames = analyzer.getNodes();
        } catch (AnalyzerException e) {
            throw new RuntimeException(e);
        }
        this.parent = parent;
        this.children = new ArrayList<>();
        insnNodes = new AbstractInsnNode[this.frames.length];
    }

    private void collectLabels() {
        if (mv.instructions != null) {
            for (AbstractInsnNode instruction : mv.instructions) {
                if (instruction instanceof LabelNode) {
                    LabelNode labelNode = (LabelNode) instruction;
                    cloneLabels.put(labelNode, new LabelNode(new Label()));
                }
            }
        }
    }

    public MethodContext createChild(MethodNode methodNode) {
        return new MethodContext(classContext, methodNode, this);
    }

    public MethodNode getMv() {
        return mv;
    }

    public BranchAnalyzer.Node<BasicValue>[] getFrames() {
        return frames;
    }

    public boolean isStatic() {
        return (mv.access & Opcodes.ACC_STATIC) != 0;
    }

    private MethodContext getRootMethodContext() {
        return parent != null ? parent.getRootMethodContext() : this;
    }

    public String nextLambdaName() {
        String rootMethodName = getRootMethodContext().getMv().name;
        return classContext.nextLambdaName(rootMethodName);
    }

    public Type classType() {
        return Type.getObjectType(classContext.getName());
    }

    public <T extends AbstractInsnNode> T cloneInsn(T node) {
        //noinspection unchecked
        return (T) node.clone(cloneLabels);
    }

    public void replaceLabel(LabelNode from, LabelNode to) {
        cloneLabels.put(from, to);
    }

    public void updateLocals(int locals) {
        localsToUpdate.add(locals);
    }

    public void updateStacks(int stacks) {
        stacksToUpdate.add(stacks);
    }

    public void updateMax() {
        mv.maxLocals = Math.max(mv.maxLocals, localsToUpdate.stream().mapToInt(i -> i).max().orElse(0));
        mv.maxStack = Math.max(mv.maxStack, stacksToUpdate.stream().mapToInt(i -> i).max().orElse(0));
    }

    public void addLambdaContext(MethodContext methodContext) {
        this.classContext.addLambda(methodContext);
    }


    private void clearInsnList(InsnList insnList) {
        ListIterator<AbstractInsnNode> iterator = insnList.iterator();
        while (iterator.hasNext()) {
            iterator.next();
            iterator.remove();
        }
    }

    public void process() {
        BranchAnalyzer.Node<BasicValue>[] nodes = getFrames();
        if (nodes.length > 0) {
            loops = GraphUtils.tarjan(nodes);
            Arrays.fill(insnNodes, null);
            process(nodes[0]);
            clearInsnList(getMv().instructions);
            InsnList newInsnList = new InsnList();
            for (AbstractInsnNode insnNode : insnNodes) {
                if (insnNode != null) {
                    if (insnNode instanceof PackageInsnNode) {
                        PackageInsnNode packageInsnNode = (PackageInsnNode) insnNode;
                        for (AbstractInsnNode node : packageInsnNode.getInsnNodes()) {
                            newInsnList.add(node);
                        }
                    } else {
                        newInsnList.add(insnNode);
                    }
                }
            }
            getMv().instructions = newInsnList;
            updateMax();
            for (MethodContext child : children) {
                child.process();
            }
        }
    }

    private Set<Integer> selectScc(int index) {
        for (Set<Integer> scc : loops) {
            if (scc.contains(index)) {
                return scc.size() > 1 ? scc : null;
            }
        }
        return null;
    }

    private boolean isAwait(Set<Integer> ssc) {
        for (Integer index : ssc) {
            AbstractInsnNode insnNode = getMv().instructions.get(index);
            if (AsmHelper.isAwait(insnNode)) {
                return true;
            }
        }
        return false;
    }

    private void pushSuccessors(BranchAnalyzer.Node<? extends BasicValue> node, Deque<WithFlag<BranchAnalyzer.Node<? extends BasicValue>>> stack) {
        BranchAnalyzer.Node<? extends BasicValue>.SuccessorsImpl successors = node.createSuccessors();
        BranchAnalyzer.Node<? extends BasicValue> successor = successors.current();
        while (successor != null) {
            stack.push(WithFlag.of(successor, false));
            successors.next();
            successor = successors.current();
        }
    }

    private void process(BranchAnalyzer.Node<? extends BasicValue> node) {
        Deque<WithFlag<BranchAnalyzer.Node<? extends BasicValue>>> stack = new ArrayDeque<>();
        stack.push(WithFlag.of(node, false));
        while (!stack.isEmpty()) {
            WithFlag<BranchAnalyzer.Node<? extends BasicValue>> withFlag = stack.pop();
            BranchAnalyzer.Node<? extends BasicValue> root = withFlag.getData();
            int index = root.getIndex();
            AbstractInsnNode insnNode = getMv().instructions.get(index);
            Set<Integer> scc = selectScc(index);
            int label;
            if (scc != null && isAwait(scc)) {
                label = 2;
            } else if (AsmHelper.isAwait(insnNode)) {
                label = 1;
            } else {
                label = 0;
            }
            boolean visited = withFlag.isFlag();
            if (visited) {
                if (label == 2) {
                    AbstractInsnNode newInsnNode = processLoopNode(root);
                    insnNodes[index] = newInsnNode;
                } else if (label == 1) {
                    AbstractInsnNode newInsnNode = processAwaitNode(root);
                    insnNodes[index] = newInsnNode;
                } else {
                    insnNodes[index] = insnNode;
                }
            } else {
                if (label == 0) {
                    pushSuccessors(root, stack);
                }
                stack.push(WithFlag.of(root, true));
            }
        }
    }

    private List<AbstractInsnNode> collectSuccessors(
            BranchAnalyzer.Node<? extends BasicValue> root,
            BiFunction<AbstractInsnNode, BranchAnalyzer.Node<? extends BasicValue>, AbstractInsnNode> mapper
    ) {
        AbstractInsnNode[] insnNodes = new AbstractInsnNode[getFrames().length];
        Deque<WithFlag<BranchAnalyzer.Node<? extends BasicValue>>> stack = new ArrayDeque<>();
        stack.push(WithFlag.of(root, false));
        while (!stack.isEmpty()) {
            WithFlag<BranchAnalyzer.Node<? extends BasicValue>> withFlag = stack.pop();
            BranchAnalyzer.Node<? extends BasicValue> node = withFlag.getData();
            boolean visited = withFlag.isFlag();
            int index = node.getIndex();
            if (visited) {
                AbstractInsnNode insnNode = getMv().instructions.get(index);
                insnNodes[index] = mapper.apply(insnNode, node);
                // if insnNodes[index] == null, may cause infinite loop.
                assert insnNodes[index] != null;
            } else {
                BranchAnalyzer.Node<? extends BasicValue>.SuccessorsImpl successors = node.createSuccessors();
                BranchAnalyzer.Node<? extends BasicValue> successor = successors.current();
                while (successor != null) {
                    if (insnNodes[successor.getIndex()] == null) {
                        stack.push(WithFlag.of(successor, false));
                    }
                    successors.next();
                    successor = successors.current();
                }
                stack.push(WithFlag.of(node, true));
            }
        }
        List<AbstractInsnNode> insnList = new LinkedList<>();
        List<AbstractInsnNode> preInsnList = new LinkedList<>();
        int index = root.getIndex();
        int i = 0;
        boolean reconnect = false;
        for (AbstractInsnNode insnNode : insnNodes) {
            if (insnNode != null && i != index) {
                List<AbstractInsnNode> target;
                if (i < index) {
                    target = preInsnList;
                    if (i == index - 1) {
                        BranchAnalyzer.Node<BasicValue> frame = getFrames()[i];
                        if (frame.getSuccessors().contains(root)) {
                            reconnect = true;
                        }
                    }
                } else {
                    target = insnList;
                }
                if (insnNode instanceof PackageInsnNode) {
                    PackageInsnNode packageInsnNode = (PackageInsnNode) insnNode;
                    target.addAll(packageInsnNode.getInsnNodes());

                } else {
                    target.add(insnNode);
                }
            }
            ++i;
        }
        LabelNode reconnectLabel = new LabelNode();
        if (reconnect) {
            insnList.add(0, reconnectLabel);
        }
        insnList.addAll(preInsnList);
        if (reconnect) {
            insnList.add(new JumpInsnNode(Opcodes.GOTO, reconnectLabel));
        }
        return insnList;
    }

    private MethodNode createLambdaNode(List<Type> arguments) {
        int access = Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC;
        if (isStatic()) {
            access |= Opcodes.ACC_STATIC;
        }
        return new MethodNode(
                Constants.ASM_VERSION,
                access,
                nextLambdaName(),
                Type.getMethodDescriptor(Constants.JPROMISE_DESC, arguments.toArray(new Type[0])),
                null,
                new String[] { Constants.THROWABLE_NAME }
        );
    }

    private void calcExtraArgumentsType(int validLocals, BranchAnalyzer.Node<? extends BasicValue> node, List<Type> arguments, boolean allStack) {
        // locals: this?, x, y, z.
        // x, y, z -> arguments
        int start = isStatic() ? 0 : 1;
        for (int i = start; i < validLocals;) {
            BasicValue value = node.getLocal(i);
            Type type = value.getType();
            if (type != null) {
                arguments.add(type);
                i += type.getSize();
            } else {
                arguments.add(Constants.OBJECT_DESC);
                ++i;
            }
        }
        int stackSize = node.getStackSize();
        int iMax = allStack ? stackSize : (stackSize - 1);
        // stack: a, b, c or a, b -> arguments
        for (int i = 0; i < iMax; ++i) {
            BasicValue value = node.getStack(i);
            Type type = value.getType();
            if (type != null) {
                arguments.add(type);
            } else {
                arguments.add(Constants.OBJECT_DESC);
            }
        }
    }

    private List<Type> calcAwaitArgumentsType(int validLocals, BranchAnalyzer.Node<? extends BasicValue> frame) {
        // stack: a, b, promise | locals: this?, x, y, z
        List<Type> arguments = new ArrayList<>();
        calcExtraArgumentsType(validLocals, frame, arguments, false);
        // await type -> arguments
        arguments.add(Constants.OBJECT_DESC);
        // context -> arguments
        arguments.add(Constants.JCONTEXT_DESC);
        // x, y, z, a, b, await type, context
        return arguments;
    }

    private List<Type> calcLoopArgumentsType(int validLocals, BranchAnalyzer.Node<? extends BasicValue> frame) {
        // stack: a, b, c | locals: this?, x, y, z
        List<Type> arguments = new ArrayList<>();
        calcExtraArgumentsType(validLocals, frame, arguments, true);
        // JPortal type -> arguments
        arguments.add(Constants.JPORTAL_DESC);
        arguments.add(Constants.JCONTEXT_DESC);
        // x, y, z, a, b, c, JPortal, JContext
        return arguments;
    }

    private void restoreStack(List<AbstractInsnNode> insnNodes, BranchAnalyzer.Node<? extends BasicValue> node, int maxLocals, int num) {
        for (int i = 0, iLocal = maxLocals; i < num; ++i) {
            BasicValue value = node.getStack(i);
            Type type = value.getType();
            if (type != null) {
                iLocal -= type.getSize();
                insnNodes.add(new VarInsnNode(type.getOpcode(Opcodes.ILOAD), iLocal));
            } else {
                --iLocal;
                insnNodes.add(new VarInsnNode(Opcodes.ALOAD, iLocal));
            }
        }
    }

    private AbstractInsnNode processAwaitNode(BranchAnalyzer.Node<? extends BasicValue> node) {
        PackageInsnNode packageInsnNode = new PackageInsnNode();
        List<AbstractInsnNode> insnNodes = packageInsnNode.getInsnNodes();
        int validLocals = AsmHelper.calcValidLocals(node);
        int stackSize = node.getStackSize();
        // stack: promise | locals: this?, x, y, z
        if (stackSize == 1) {
            if (!isStatic()) {
                // load this to stack
                // stack: promise | locals: this, x, y, z -> stack: promise, this | locals: this, x, y, z
                insnNodes.add(new VarInsnNode(Opcodes.ALOAD, 0));
            }
            // push the previous locals to the stack except this
            // stack: promise, this? | locals: this?, x, y, z -> stack: promise, this?, x, y, z | locals: this?, x, y, z
            AsmHelper.pushLocalToStack(validLocals, isStatic(), node, insnNodes);
        }
        // stack: a, b, promise | locals: this?, x, y, z
        else {
            // store the current stack to the locals (offset by locals). the first one (index of locals) should be the promise
            // stack: a, b, promise | locals: this?, x, y, z -> stack: [] | locals: this?, x, y, z, promise, b, a
            int maxLocals = AsmHelper.storeStackToLocal(validLocals, node, insnNodes);
            updateLocals(maxLocals);
            // push the target promise to stack
            // stack: [] | locals: this?, x, y, z, promise, b, a -> stack: promise | locals: this?, x, y, z, promise, b, a
            insnNodes.add(new VarInsnNode(Opcodes.ALOAD, validLocals));
            if (!isStatic()) {
                // load this to stack
                // stack: promise | locals: this, x, y, z, promise, b, a -> stack: promise, this | locals: this, x, y, z, promise, b, a
                insnNodes.add(new VarInsnNode(Opcodes.ALOAD, 0));
            }
            // push the previous locals to the stack except this
            // stack: promise, this? | locals: this?, x, y, z, promise, b, a -> stack: promise, this?, x, y, z | locals: this?, x, y, z, promise, b, a
            AsmHelper.pushLocalToStack(validLocals, isStatic(), node, insnNodes);
            // push the previous stack from locals to the stack, except the previous stack top, which is the promise.
            // stack: promise, this?, x, y, z | locals: this?, x, y, z, promise, b, a -> stack: promise, this?, x, y, z, a, b | locals: this?, x, y, z, promise, b, a
            restoreStack(insnNodes, node, maxLocals, stackSize - 1);
        }
        updateStacks(stackSize + validLocals);
        List<Type> arguments = calcAwaitArgumentsType(validLocals, node);
        MethodNode lambdaNode = createLambdaNode(arguments);
        insnNodes.add(LambdaUtils.invokeJAsyncPromiseFunction1(
                classType(),
                lambdaNode.name,
                isStatic(),
                arguments.subList(0, arguments.size() - 2).toArray(new Type[arguments.size() - 2])
        ));
        insnNodes.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                Constants.JPROMISE_NAME,
                Constants.JPROMISE_THEN_WITH_CONTEXT_NAME,
                Constants.JPROMISE_THEN_WITH_CONTEXT_DESC.getDescriptor())
        );
        insnNodes.add(new InsnNode(Opcodes.ARETURN));
        List<AbstractInsnNode> successors = collectSuccessors(node, (in, n) -> cloneInsn(in));
        buildLambda(lambdaNode, arguments, successors, validLocals, stackSize, null);
        return packageInsnNode;
    }

    private AbstractInsnNode processLoopNode(BranchAnalyzer.Node<? extends BasicValue> node) {
        AbstractInsnNode insnNode = getMv().instructions.get(node.getIndex());
        if (!(insnNode instanceof LabelNode)) {
            // 因为这个指令是至少2个指令的后继，只有 LabelNode 可以是多个指令的后继
            throw new IllegalStateException("This is impossible!");
        }
        LabelNode labelNode = (LabelNode) insnNode;
        PackageInsnNode packageInsnNode = new PackageInsnNode();
        List<AbstractInsnNode> insnNodes = packageInsnNode.getInsnNodes();
        // 因为是 LabelNode，对 frame 不做任何改变，所以执行后 frame 就是 执行前 frame
        int validLocals = AsmHelper.calcValidLocals(node);
        int stackSize = node.getStackSize();
        // stack: [] | locals: this?, x, y, z
        if (stackSize == 0) {
            if (!isStatic()) {
                // load this to stack
                // stack: [] | locals: this, x, y, z -> stack: this | locals: this, x, y, z
                insnNodes.add(new VarInsnNode(Opcodes.ALOAD, 0));
            }
            // stack: this? | locals: this?, x, y, z -> stack: this?, x, y, z | locals: this?, x, y, z
            AsmHelper.pushLocalToStack(validLocals, isStatic(), node, insnNodes);
        }
        // stack: a, b, c | locals: this?, x, y, z
        else {
            // store the current stack to the locals (offset by locals).
            // stack: a, b, c | locals: this?, x, y, z -> stack: [] | locals: this?, x, y, z, c, b, a
            int maxLocals = AsmHelper.storeStackToLocal(validLocals, node, insnNodes);
            updateLocals(maxLocals);
            if (!isStatic()) {
                // load this to stack
                // stack: [] | locals: this, x, y, z, c, b, a -> stack: this | locals: this, x, y, z, c, b, a
                insnNodes.add(new VarInsnNode(Opcodes.ALOAD, 0));
            }
            // push the previous locals to the stack except this
            // stack: this? | locals: this?, x, y, z, c, b, a -> stack: this?, x, y, z | locals: this?, x, y, z, c, b, a
            AsmHelper.pushLocalToStack(validLocals, isStatic(), node, insnNodes);
            // push the previous stack from locals to the stack.
            // stack: this?, x, y, z | locals: this?, x, y, z, c, b, a -> stack: this?, x, y, z, a, b, c | locals: this?, x, y, z, c, b, a
            restoreStack(insnNodes, node, maxLocals, stackSize);
        }
        updateStacks(stackSize + validLocals);
        // x, y, z, a, b, c, JPortal, JContext
        List<Type> arguments = calcLoopArgumentsType(validLocals, node);
        MethodNode lambdaNode = createLambdaNode(arguments);
        insnNodes.add(LambdaUtils.invokeJAsyncPortalTask(
                classType(),
                lambdaNode.name,
                isStatic(),
                arguments.subList(0, arguments.size() - 2).toArray(new Type[arguments.size() - 2])
        ));
        insnNodes.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                Constants.JPROMISE_NAME,
                Constants.JPROMISE_PORTAL_NAME,
                Constants.JPROMISE_PORTAL_DESC.getDescriptor(),
                true
        ));
        insnNodes.add(new InsnNode(Opcodes.ARETURN));
        LabelNode portalLabel = new LabelNode();
        replaceLabel(labelNode, portalLabel);
        List<AbstractInsnNode> successors = collectSuccessors(node, (in, n) -> {
            AbstractInsnNode cloneInsn = cloneInsn(in);
            if (cloneInsn instanceof FrameNode) {
                FrameNode frameNode = (FrameNode) cloneInsn;
                frameNode.type = frameNode.type == Opcodes.F_NEW ? Opcodes.F_FULL : frameNode.type;
            }
            return cloneInsn;
        });
        buildLambda(lambdaNode, arguments, successors, validLocals, stackSize, portalLabel);
        return packageInsnNode;
    }

    private void buildLambda(MethodNode lambdaNode, List<Type> arguments, List<AbstractInsnNode> insnList, int locals, int stacks, LabelNode portalLabel) {
        lambdaNode.visitCode();
        boolean isStatic = isStatic();
        int offset = isStatic ? 0 : 1;
        // restore stack.
        // 1. await:
        // arguments: x, y, z, a, b, await type, context -> stack: a, b, await result
        // locals: this?, x, y, z, a, b, await type, context
        // 2. loop:
        // arguments: x, y, z, a, b, c, portal, context -> stack: a, b, c
        // locals: this?, x, y, z, a, b, c, portal, context
        int iExtra1 = -1, iExtra2 = -1;
        Type tExtra1 = null, tExtra2 = null;
        AbstractInsnNode[] insnNodes = new AbstractInsnNode[stacks];
        int j = offset, k = 0;
        for (Type type : arguments) {
            if (j >= locals && j < locals + stacks) {
                if (type != null) {
                    insnNodes[k++] = new VarInsnNode(type.getOpcode(Opcodes.ILOAD), j);
                } else {
                    insnNodes[k++] = new InsnNode(Opcodes.ACONST_NULL);
                }
            } else if (j == locals + stacks) {
                iExtra1 = j;
                tExtra1 = type != null ? type : Constants.OBJECT_DESC;
            } else if (j == locals + stacks + 1) {
                iExtra2 = j;
                tExtra2 = type != null ? type : Constants.OBJECT_DESC;
            }
            if (type != null) {
                j += type.getSize();
            } else {
                ++j;
            }
        }
        if (iExtra1 < 0 || (portalLabel != null && iExtra2 < 0)) {
            throw new IllegalStateException("This is impossible.");
        }
        int maxStacks = getMv().maxStack;
        int newMaxLocals = j;
        int newMaxStacks = stacks;
        int maxLocals = getMv().maxLocals;
        if (maxLocals > iExtra1) {
            lambdaNode.instructions.add(new VarInsnNode(tExtra1.getOpcode(Opcodes.ILOAD), iExtra1));
            lambdaNode.instructions.add(new VarInsnNode(tExtra1.getOpcode(Opcodes.ISTORE), maxLocals));
            iExtra1 = maxLocals;
            maxLocals += tExtra1.getSize();
            if (portalLabel != null) {
                lambdaNode.instructions.add(new VarInsnNode(tExtra2.getOpcode(Opcodes.ILOAD), iExtra2));
                lambdaNode.instructions.add(new VarInsnNode(tExtra2.getOpcode(Opcodes.ISTORE), maxLocals));
                maxLocals += tExtra2.getSize();
            }
            newMaxStacks = Math.max(newMaxLocals, 1);
        }
        for (AbstractInsnNode insnNode : insnNodes) {
            lambdaNode.instructions.add(insnNode);
        }
        for (AbstractInsnNode insnNode : insnList) {
            lambdaNode.instructions.add(insnNode);
        }
        if (portalLabel != null) {
            newMaxStacks = Math.max(newMaxStacks, stacks + 1);
            int localSize = iExtra1 + 1;
            Object[] localTypes = new Object[localSize];
            Arrays.fill(localTypes, Opcodes.NULL);
            if (!isStatic) {
                localTypes[0] = classContext.getName();
            }
            localTypes[iExtra1] = Constants.JPORTAL_NAME;
            lambdaNode.instructions.add(portalLabel);
            lambdaNode.instructions.add(new FrameNode(Opcodes.F_FULL, localSize, localTypes, 0, null));
            lambdaNode.instructions.add(new VarInsnNode(Opcodes.ALOAD, iExtra1));
            lambdaNode.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, Constants.JPORTAL_NAME, Constants.JPORTAL_JUMP_NAME, Constants.JPORTAL_JUMP_DESC.getDescriptor()));
            lambdaNode.instructions.add(new InsnNode(Opcodes.ARETURN));
        }
        AbstractInsnNode firstNode = lambdaNode.instructions.getFirst();
        if (firstNode instanceof FrameNode) {
            lambdaNode.instructions.remove(firstNode);
        }
        lambdaNode.visitMaxs(Math.max(maxStacks, newMaxStacks), Math.max(maxLocals, newMaxLocals));
        lambdaNode.visitEnd();

        MethodContext childContext = createChild(lambdaNode);
        children.add(childContext);
        addLambdaContext(childContext);
    }

}
