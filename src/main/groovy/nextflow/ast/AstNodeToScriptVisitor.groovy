/*
 * Copyright (c) 2012, the authors.
 *
 *   This file is part of 'Nextflow'.
 *
 *   Nextflow is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   Nextflow is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with Nextflow.  If not, see <http://www.gnu.org/licenses/>.
 */
package nextflow.ast

import org.codehaus.groovy.ast.*
import org.codehaus.groovy.ast.expr.*
import org.codehaus.groovy.ast.stmt.*
import org.codehaus.groovy.classgen.BytecodeExpression
import java.lang.reflect.Modifier
import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.classgen.GeneratorContext
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.classgen.Verifier

/**
 * An adapter from ASTNode tree to source code.
 *
 * @author Hamlet D'Arcy
 */
class AstNodeToScriptVisitor extends CompilationUnit.PrimaryClassNodeOperation implements GroovyCodeVisitor, GroovyClassVisitor {

    private Writer _out
    Stack<String> classNameStack = new Stack<String>();
    String _indent = ""
    boolean readyToIndent = true
    boolean showScriptFreeForm
    boolean showScriptClass
    boolean scriptHasBeenVisited

    def AstNodeToScriptVisitor(Writer writer, boolean showScriptFreeForm = true, boolean showScriptClass = true) {
        this._out = writer;
        this.showScriptFreeForm = showScriptFreeForm
        this.showScriptClass = showScriptClass
        this.scriptHasBeenVisited = false
    }

    void call(SourceUnit source, GeneratorContext context, ClassNode classNode) {

        visitPackage(source?.getAST()?.getPackage())

        visitAllImports(source)

        if (showScriptFreeForm && !scriptHasBeenVisited) {
            scriptHasBeenVisited = true
            source?.getAST()?.getStatementBlock()?.visit(this)
        }
        if (showScriptClass || !classNode.isScript()) {
            visitClass classNode
        }
    }

    private def visitAllImports(SourceUnit source) {
        boolean staticImportsPresent = false
        boolean importsPresent = false

        source?.getAST()?.getStaticImports()?.values()?.each {
            visitImport(it)
            staticImportsPresent = true
        }
        source?.getAST()?.getStaticStarImports()?.values()?.each {
            visitImport(it)
            staticImportsPresent = true
        }

        if (staticImportsPresent) {
            printDoubleBreak()
        }

        source?.getAST()?.getImports()?.each {
            visitImport(it)
            importsPresent = true
        }
        source?.getAST()?.getStarImports()?.each {
            visitImport(it)
            importsPresent = true
        }
        if (importsPresent) {
            printDoubleBreak()
        }
    }


    void print(parameter) {
        def output = parameter.toString()

        if (readyToIndent) {
            _out.print _indent
            readyToIndent = false
            while (output.startsWith(' ')) {
                output = output[1..-1]  // trim left
            }
        }
        if (_out.toString().endsWith(' ')) {
            if (output.startsWith(' ')) {
                output = output[1..-1]
            }
        }
        _out.print output
    }

    def println(parameter) {
        throw new UnsupportedOperationException('Wrong API')
    }

    def indented(Closure block) {
        String startingIndent = _indent
        _indent = _indent + "    "
        block()
        _indent = startingIndent
    }

    def printLineBreak() {
        if (!_out.toString().endsWith('\n')) {
            _out.print '\n'
        }
        readyToIndent = true
    }

    def printDoubleBreak() {
        if (_out.toString().endsWith('\n\n')) {
            // do nothing
        } else if (_out.toString().endsWith('\n')) {
            _out.print '\n'
        } else {
            _out.print '\n'
            _out.print '\n'
        }
        readyToIndent = true
    }

    void visitPackage(PackageNode packageNode) {

        if (packageNode) {

            packageNode.annotations?.each {
                visitAnnotationNode(it)
                printLineBreak()
            }

            if (packageNode.text.endsWith(".")) {
                print packageNode.text[0..-2]
            } else {
                print packageNode.text
            }
            printDoubleBreak()
        }
    }

    void visitImport(ImportNode node) {
        if (node) {
            node.annotations?.each {
                visitAnnotationNode(it)
                printLineBreak()
            }
            print node.text
            printLineBreak()
        }
    }

    @Override
    public void visitClass(ClassNode node) {

        classNameStack.push(node.name)

        node?.annotations?.each {
            visitAnnotationNode(it)
            printLineBreak()
        }

        visitModifiers(node.modifiers)
        print "class $node.name"
        visitGenerics node?.genericsTypes
        boolean first = true
        node.interfaces?.each {
            if (!first) {
                print ', '
            } else {
                print ' implements '
            }
            first = false
            visitType it
        }
        print ' extends '
        visitType node.superClass
        print " { "
        printDoubleBreak()

        indented {
            node?.properties?.each { visitProperty(it) }
            printLineBreak()
            node?.fields?.each { visitField(it) }
            printDoubleBreak()
            node?.declaredConstructors?.each { visitConstructor(it) }
            printLineBreak()
            node?.methods?.each { visitMethod(it) }
        }
        print '}'
        printLineBreak()
        classNameStack.pop()
    }

    private void visitGenerics(GenericsType[] generics) {

        if (generics) {
            print '<'
            boolean first = true
            generics.each { GenericsType it ->
                if (!first) {
                    print ', '
                }
                first = false
                print it.name
                if (it.upperBounds) {
                    print ' extends '
                    boolean innerFirst = true
                    it.upperBounds.each { ClassNode upperBound ->
                        if (!innerFirst) {
                            print ' & '
                        }
                        innerFirst = false
                        visitType upperBound
                    }
                }
                if (it.lowerBound) {
                    print ' super '
                    visitType it.lowerBound
                }
            }
            print '>'
        }
    }

    @Override
    public void visitConstructor(ConstructorNode node) {
        visitMethod(node)
    }

    private String visitParameters(parameters) {
        boolean first = true

        parameters.each { Parameter it ->
            if (!first) {
                print ', '
            }
            first = false

            it.annotations?.each {
                visitAnnotationNode(it)
                print(' ')
            }

            visitModifiers(it.modifiers)
            visitType it.type
            print ' ' + it.name
            if (it.initialExpression && !(it.initialExpression instanceof EmptyExpression)) {
                print ' = '
                it.initialExpression.visit this
            }
        }
    }

    @Override
    public void visitMethod(MethodNode node) {
        node?.annotations?.each {
            visitAnnotationNode(it)
            printLineBreak()
        }

        visitModifiers(node.modifiers)
        if (node.name == '<init>') {
            print "${classNameStack.peek()}("
            visitParameters(node.parameters)
            print ") {"
            printLineBreak()
        } else if (node.name == '<clinit>') {
            print '{ ' // will already have 'static' from modifiers
            printLineBreak()
        } else {
            visitType node.returnType
            print " $node.name("
            visitParameters(node.parameters)
            print ")"
            if (node.exceptions) {
                boolean first = true
                print ' throws '
                node.exceptions.each {
                    if (!first) {
                        print ', '
                    }
                    first = false
                    visitType it
                }
            }
            print " {"
            printLineBreak()
        }

        indented {
            node?.code?.visit(this)
        }
        printLineBreak()
        print '}'
        printDoubleBreak()
    }

    private def visitModifiers(int modifiers) {
        if (Modifier.isAbstract(modifiers)) {
            print 'abstract '
        }
        if (Modifier.isFinal(modifiers)) {
            print 'final '
        }
        if (Modifier.isInterface(modifiers)) {
            print 'interface '
        }
        if (Modifier.isNative(modifiers)) {
            print 'native '
        }
        if (Modifier.isPrivate(modifiers)) {
            print 'private '
        }
        if (Modifier.isProtected(modifiers)) {
            print 'protected '
        }
        if (Modifier.isPublic(modifiers)) {
            print 'public '
        }
        if (Modifier.isStatic(modifiers)) {
            print 'static '
        }
        if (Modifier.isSynchronized(modifiers)) {
            print 'synchronized '
        }
        if (Modifier.isTransient(modifiers)) {
            print 'transient '
        }
        if (Modifier.isVolatile(modifiers)) {
            print 'volatile '
        }
    }

    @Override
    public void visitField(FieldNode node) {
        node?.annotations?.each {
            visitAnnotationNode(it)
            printLineBreak()
        }
        visitModifiers(node.modifiers)
        visitType node.type
        print " $node.name "
        // do not print initial expression, as this is executed as part of the constructor, unless on static constant
        Expression exp = node.initialValueExpression
        if (exp instanceof ConstantExpression) exp = Verifier.transformToPrimitiveConstantIfPossible(exp)
        ClassNode type = exp?.type
        if (Modifier.isStatic(node.modifiers) && Modifier.isFinal(node.getModifiers())
                && exp instanceof ConstantExpression
                && type == node.type
                && ClassHelper.isStaticConstantInitializerType(type)) {
            // GROOVY-5150: final constants may be initialized directly
            print " = "
            if (ClassHelper.STRING_TYPE == type) {
                print "'"+node.initialValueExpression.text.replaceAll("'", "\\\\'")+"'"
            } else if (ClassHelper.char_TYPE == type) {
                print "'${node.initialValueExpression.text}'"
            } else {
                print node.initialValueExpression.text
            }
        }
        printLineBreak()
    }

    public void visitAnnotationNode(AnnotationNode node) {
        print '@' + node?.classNode?.name
        if (node?.members) {
            print '('
            boolean first = true
            node.members.each { String name, Expression value ->
                if (first) {
                    first = false
                } else {
                    print ', '
                }
                print name + ' = '
                value.visit(this)
            }
            print ')'
        }

    }

    @Override
    public void visitProperty(PropertyNode node) {
        // is a FieldNode, avoid double dispatch
    }

    @Override
    public void visitBlockStatement(BlockStatement block) {
        block?.statements?.each {
            it.visit(this);
            printLineBreak()
        }
        if (!_out.toString().endsWith('\n')) {
            printLineBreak()
        }
    }

    @Override
    public void visitForLoop(ForStatement statement) {

        print 'for ('
        if (statement?.variable != ForStatement.FOR_LOOP_DUMMY) {
            visitParameters([statement.variable])
            print ' : '
        }

        if (statement?.collectionExpression instanceof ListExpression) {
            statement?.collectionExpression?.visit this
        } else {
            statement?.collectionExpression?.visit this
        }
        print ') {'
        printLineBreak()
        indented {
            statement?.loopBlock?.visit this
        }
        print '}'
        printLineBreak()
    }

    @Override
    public void visitIfElse(IfStatement ifElse) {
        print 'if ('
        ifElse?.booleanExpression?.visit this
        print ') {'
        printLineBreak()
        indented {
            ifElse?.ifBlock?.visit this
        }
        printLineBreak()
        if (ifElse?.elseBlock && !(ifElse.elseBlock instanceof EmptyStatement)) {
            print "} else {"
            printLineBreak()
            indented {
                ifElse?.elseBlock?.visit this
            }
            printLineBreak()
        }
        print '}'
        printLineBreak()
    }

    @Override
    public void visitExpressionStatement(ExpressionStatement statement) {
        statement.expression.visit this
    }

    @Override
    public void visitReturnStatement(ReturnStatement statement) {
        printLineBreak()
        print "return "
        statement.getExpression().visit(this);
        printLineBreak()
    }

    @Override
    public void visitSwitch(SwitchStatement statement) {
        print 'switch ('
        statement?.expression?.visit this
        print ') {'
        printLineBreak()
        indented {
            statement?.caseStatements?.each {
                visitCaseStatement it
            }
            if (statement?.defaultStatement) {
                print 'default: '
                printLineBreak()
                statement?.defaultStatement?.visit this
            }
        }
        print '}'
        printLineBreak()
    }

    @Override
    public void visitCaseStatement(CaseStatement statement) {
        print 'case '
        statement?.expression?.visit this
        print ':'
        printLineBreak()
        indented {
            statement?.code?.visit this
        }
    }

    @Override
    public void visitBreakStatement(BreakStatement statement) {
        print 'break'
        printLineBreak()
    }

    @Override
    public void visitContinueStatement(ContinueStatement statement) {
        print 'continue'
        printLineBreak()
    }

    @Override
    public void visitMethodCallExpression(MethodCallExpression expression) {

        Expression objectExp = expression.getObjectExpression()
        if (objectExp instanceof VariableExpression) {
            visitVariableExpression(objectExp, false)
        } else {
            objectExp.visit(this);
        }
        if (expression.spreadSafe) {
            print '*'
        }
        if (expression.safe) {
            print '?'
        }
        print '.'
        Expression method = expression.getMethod()
        if (method instanceof ConstantExpression) {
            visitConstantExpression(method, true)
        } else {
            method.visit(this);
        }
        expression.getArguments().visit(this)
    }

    @Override
    public void visitStaticMethodCallExpression(StaticMethodCallExpression expression) {
        print expression?.ownerType?.name + "." + expression?.method
        if (expression?.arguments instanceof VariableExpression || expression?.arguments instanceof MethodCallExpression) {
            print '('
            expression?.arguments?.visit this
            print ')'
        } else {
            expression?.arguments?.visit this
        }
    }

    @Override
    public void visitConstructorCallExpression(ConstructorCallExpression expression) {
        if (expression?.isSuperCall()) {
            print 'super'
        } else if (expression?.isThisCall()) {
            print 'this '
        } else {
            print 'new '
            visitType expression?.type
        }
        expression?.arguments?.visit this
    }

    @Override
    public void visitBinaryExpression(BinaryExpression expression) {
        expression?.leftExpression?.visit this
        print " $expression.operation.text "
        expression.rightExpression.visit this

        if (expression?.operation?.text == '[') {
            print ']'
        }
    }

    @Override
    public void visitPostfixExpression(PostfixExpression expression) {
        print '('
        expression?.expression?.visit this
        print ')'
        print expression?.operation?.text
    }

    @Override
    public void visitPrefixExpression(PrefixExpression expression) {
        print expression?.operation?.text
        print '('
        expression?.expression?.visit this
        print ')'
    }


    @Override
    public void visitClosureExpression(ClosureExpression expression) {
        print '{ '
        if (expression?.parameters) {
            visitParameters(expression?.parameters)
            print ' ->'
        }
        printLineBreak()
        indented {
            expression?.code?.visit this
        }
        print '}'
    }

    @Override
    public void visitTupleExpression(TupleExpression expression) {
        print '('
        visitExpressionsAndCommaSeparate(expression?.expressions)
        print ')'
    }

    @Override
    public void visitRangeExpression(RangeExpression expression) {
        print '('
        expression?.from?.visit this
        print '..'
        expression?.to?.visit this
        print ')'
    }

    @Override
    public void visitPropertyExpression(PropertyExpression expression) {
        expression?.objectExpression?.visit this
        if (expression?.spreadSafe) {
            print '*'
        } else if (expression?.isSafe()) {
            print '?'
        }
        print '.'
        if (expression?.property instanceof ConstantExpression) {
            visitConstantExpression(expression?.property, true)
        } else {
            expression?.property?.visit this
        }
    }

    @Override
    public void visitAttributeExpression(AttributeExpression attributeExpression) {
        visitPropertyExpression attributeExpression
    }

    @Override
    public void visitFieldExpression(FieldExpression expression) {
        print expression?.field?.name
    }

    public void visitConstantExpression(ConstantExpression expression, boolean unwrapQuotes = false) {
        if (expression.value instanceof String && !unwrapQuotes) {
            // string reverse escaping is very naive
            def escaped = ((String) expression.value).replaceAll('\n', '\\\\n').replaceAll("'", "\\\\'")
            print "'$escaped'"
        } else {
            print expression.value
        }
    }

    @Override
    public void visitClassExpression(ClassExpression expression) {
        print expression.text
    }

    public void visitVariableExpression(VariableExpression expression, boolean spacePad = true) {

        if (spacePad) {
            print ' ' + expression.name + ' '
        } else {
            print expression.name
        }
    }

    @Override
    public void visitDeclarationExpression(DeclarationExpression expression) {
        // handle multiple assignment expressions
        if (expression?.leftExpression instanceof ArgumentListExpression) {
            print 'def '
            visitArgumentlistExpression expression?.leftExpression, true
            print " $expression.operation.text "
            expression.rightExpression.visit this

            if (expression?.operation?.text == '[') {
                print ']'
            }
        } else {
            visitType expression?.leftExpression?.type
            visitBinaryExpression expression // is a BinaryExpression
        }
    }

    @Override
    public void visitGStringExpression(GStringExpression expression) {
        print '"' + expression.text + '"'
    }

    @Override
    public void visitSpreadExpression(SpreadExpression expression) {
        print '*'
        expression?.expression?.visit this
    }

    @Override
    public void visitNotExpression(NotExpression expression) {
        print '!('
        expression?.expression?.visit this
        print ')'
    }

    @Override
    public void visitUnaryMinusExpression(UnaryMinusExpression expression) {
        print '-('
        expression?.expression?.visit this
        print ')'
    }

    @Override
    public void visitUnaryPlusExpression(UnaryPlusExpression expression) {
        print '+('
        expression?.expression?.visit this
        print ')'
    }

    @Override
    public void visitCastExpression(CastExpression expression) {
        print '(('
        expression?.expression?.visit this
        print ') as '
        visitType(expression?.type)
        print ')'

    }

    /**
     * Prints out the type, safely handling arrays.
     * @param classNode
     *      classnode
     */
    public void visitType(ClassNode classNode) {
        def name = classNode.name
        if (name =~ /^\[+L/ && name.endsWith(";")) {
            int numDimensions = name.indexOf('L')
            print "${classNode.name[(numDimensions + 1)..-2]}" + ('[]' * numDimensions)
        } else {
            print name
        }
        visitGenerics classNode?.genericsTypes
    }

    public void visitArgumentlistExpression(ArgumentListExpression expression, boolean showTypes = false) {
        print '('
        int count = expression?.expressions?.size()
        expression.expressions.each {
            if (showTypes) {
                visitType it.type
                print ' '
            }
            if (it instanceof VariableExpression) {
                visitVariableExpression it, false
            } else if (it instanceof ConstantExpression) {
                visitConstantExpression it, false
            } else {
                it.visit this
            }
            count--
            if (count) print ', '
        }
        print ')'
    }

    @Override
    public void visitBytecodeExpression(BytecodeExpression expression) {
        print "/*BytecodeExpression*/"
        printLineBreak()
    }



    @Override
    public void visitMapExpression(MapExpression expression) {
        print '['
        if (expression?.mapEntryExpressions?.size() == 0) {
            print ':'
        } else {
            visitExpressionsAndCommaSeparate(expression?.mapEntryExpressions)
        }
        print ']'
    }

    @Override
    public void visitMapEntryExpression(MapEntryExpression expression) {
        if (expression?.keyExpression instanceof SpreadMapExpression) {
            print '*'            // is this correct?
        } else {
            expression?.keyExpression?.visit this
        }
        print ': '
        expression?.valueExpression?.visit this
    }

    @Override
    public void visitListExpression(ListExpression expression) {
        print '['
        visitExpressionsAndCommaSeparate(expression?.expressions)
        print ']'
    }

    @Override
    public void visitTryCatchFinally(TryCatchStatement statement) {
        print 'try {'
        printLineBreak()
        indented {
            statement?.tryStatement?.visit this
        }
        printLineBreak()
        print '} '
        printLineBreak()
        statement?.catchStatements?.each { CatchStatement catchStatement ->
            visitCatchStatement(catchStatement)
        }
        print 'finally { '
        printLineBreak()
        indented {
            statement?.finallyStatement?.visit this
        }
        print '} '
        printLineBreak()
    }

    @Override
    public void visitThrowStatement(ThrowStatement statement) {
        print 'throw '
        statement?.expression?.visit this
        printLineBreak()
    }

    @Override
    public void visitSynchronizedStatement(SynchronizedStatement statement) {
        print 'synchronized ('
        statement?.expression?.visit this
        print ') {'
        printLineBreak()
        indented {
            statement?.code?.visit this
        }
        print '}'
    }

    @Override
    public void visitTernaryExpression(TernaryExpression expression) {
        expression?.booleanExpression?.visit this
        print ' ? '
        expression?.trueExpression?.visit this
        print ' : '
        expression?.falseExpression?.visit this
    }

    @Override
    public void visitShortTernaryExpression(ElvisOperatorExpression expression) {
        visitTernaryExpression(expression)
    }

    @Override
    public void visitBooleanExpression(BooleanExpression expression) {
        expression?.expression?.visit this
    }

    @Override
    public void visitWhileLoop(WhileStatement statement) {
        print 'while ('
        statement?.booleanExpression?.visit this
        print ') {'
        printLineBreak()
        indented {
            statement?.loopBlock?.visit this
        }
        printLineBreak()
        print '}'
        printLineBreak()
    }

    @Override
    public void visitDoWhileLoop(DoWhileStatement statement) {
        print 'do {'
        printLineBreak()
        indented {
            statement?.loopBlock?.visit this
        }
        print '} while ('
        statement?.booleanExpression?.visit this
        print ')'
        printLineBreak()
    }

    @Override
    public void visitCatchStatement(CatchStatement statement) {
        print 'catch ('
        visitParameters([statement.variable])
        print ') {'
        printLineBreak()
        indented {
            statement.code?.visit this
        }
        print '} '
        printLineBreak()
    }

    @Override
    public void visitBitwiseNegationExpression(BitwiseNegationExpression expression) {
        print '~('
        expression?.expression?.visit this
        print ') '
    }


    @Override
    public void visitAssertStatement(AssertStatement statement) {
        print 'assert '
        statement?.booleanExpression?.visit this
        print ' : '
        statement?.messageExpression?.visit this
    }

    @Override
    public void visitClosureListExpression(ClosureListExpression expression) {
        boolean first = true
        expression?.expressions?.each {
            if (!first) {
                print ';'
            }
            first = false
            it.visit this
        }
    }

    @Override
    public void visitMethodPointerExpression(MethodPointerExpression expression) {
        expression?.expression?.visit this
        print '.&'
        expression?.methodName?.visit this
    }

    @Override
    public void visitArrayExpression(ArrayExpression expression) {
        print 'new '
        visitType expression?.elementType
        print '['
        visitExpressionsAndCommaSeparate(expression?.sizeExpression)
        print ']'
    }

    private void visitExpressionsAndCommaSeparate(List<? super Expression> expressions) {
        boolean first = true
        expressions?.each {
            if (!first) {
                print ', '
            }
            first = false
            it.visit this
        }
    }

    @Override
    public void visitSpreadMapExpression(SpreadMapExpression expression) {
        print '*:'
        expression?.expression?.visit this
    }
}
