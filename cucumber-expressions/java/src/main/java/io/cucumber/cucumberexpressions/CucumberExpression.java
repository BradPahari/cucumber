package io.cucumber.cucumberexpressions;

import io.cucumber.cucumberexpressions.CucumberExpressionParser.RuleType;
import org.apiguardian.api.API;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static java.util.stream.Collectors.joining;

@API(status = API.Status.STABLE)
final class CucumberExpression implements Expression {
    private static final Pattern ESCAPE_PATTERN = Pattern.compile("([\\\\^\\[({$.|?*+})\\]])");
    private static final String PARAMETER_TYPES_CANNOT_BE_OPTIONAL = "Parameter types cannot be optional: ";
    private static final String PARAMETER_TYPES_CANNOT_BE_ALTERNATIVE = "Parameter types cannot be alternative: ";
    private static final String ALTERNATIVE_MAY_NOT_BE_EMPTY = "Alternative may not be empty: ";
    private static final String OPTIONAL_MAY_NOT_BE_EMPTY = "Optional may not be empty: ";
    private static final String ALTERNATIVE_MAY_NOT_EXCLUSIVELY_CONTAIN_OPTIONALS = "Alternative may not exclusively contain optionals: ";

    private final List<ParameterType<?>> parameterTypes = new ArrayList<>();
    private final String source;
    private final TreeRegexp treeRegexp;
    private final ParameterTypeRegistry parameterTypeRegistry;

    CucumberExpression(String expression, ParameterTypeRegistry parameterTypeRegistry) {
        this.source = expression;
        this.parameterTypeRegistry = parameterTypeRegistry;

        AstBuilder builder = new AstBuilder();
        Iterable<Token> tokens = new CucumberExpressionTokenizer().tokenize(expression);
        CucumberExpressionParser<AstNode> parser = new CucumberExpressionParser<>(builder);
        AstNode ast = parser.parse(tokens);
        String pattern = rewriteToRegex(ast);
        treeRegexp = new TreeRegexp(pattern);
    }

    private String rewriteToRegex(AstNode node) {
        switch (node.getType()) {
            case Text:
                return escapeRegex(node.getText());
            case Optional:
                return rewriteOptional(node);
            case Alternation:
                return rewriteAlternation(node);
            case Alternate:
                return rewriteAlternative(node);
            case Parameter:
                return rewriteParameter(node);
            case CucumberExpression:
                return rewriteExpression(node);
            default:
                throw new IllegalArgumentException(node.getType().name());
        }
    }

    private static String escapeRegex(String text) {
        return ESCAPE_PATTERN.matcher(text).replaceAll("\\\\$1");
    }

    private String rewriteOptional(AstNode node) {
        assertNoParameters(node, PARAMETER_TYPES_CANNOT_BE_OPTIONAL);
        assertNotEmpty(node, OPTIONAL_MAY_NOT_BE_EMPTY);
        return node.getNodes().stream()
                .map(this::rewriteToRegex)
                .collect(joining("", "(?:", ")?"));
    }


    private String rewriteAlternation(AstNode node) {
        // Make sure the alternative parts aren't empty and don't contain parameter types
        for (AstNode alternative : node.getNodes()) {
            if (alternative.getNodes().isEmpty()) {
                throw new CucumberExpressionException(ALTERNATIVE_MAY_NOT_BE_EMPTY + source);
            }
            assertNoParameters(alternative, PARAMETER_TYPES_CANNOT_BE_ALTERNATIVE);
            assertNotEmpty(alternative, ALTERNATIVE_MAY_NOT_EXCLUSIVELY_CONTAIN_OPTIONALS);
        }
        return node.getNodes()
                .stream()
                .map(this::rewriteToRegex)
                .collect(joining("|", "(?:", ")"));
    }

    private String rewriteAlternative(AstNode node) {
        return node.getNodes().stream()
                .map(this::rewriteToRegex)
                .collect(joining());
    }

    private String rewriteParameter(AstNode node) {
        String name = node.getText();
        ParameterType.checkParameterTypeName(name);
        ParameterType<?> parameterType = parameterTypeRegistry.lookupByTypeName(name);
        if (parameterType == null) {
            throw new UndefinedParameterTypeException(name);
        }
        parameterTypes.add(parameterType);
        List<String> regexps = parameterType.getRegexps();
        if (regexps.size() == 1) {
            return "(" + regexps.get(0) + ")";
        }
        return regexps.stream()
                .collect(joining(")|(?:", "((?:", "))"));
    }

    private String rewriteExpression(AstNode node) {
        return node.getNodes().stream()
                .map(this::rewriteToRegex)
                .collect(joining("", "^", "$"));
    }

    private void assertNotEmpty(AstNode node, String message) {
        boolean hasTextNode = node.getNodes()
                .stream()
                .map(AstNode::getType)
                .anyMatch(type -> type == RuleType.Text);
        if (!hasTextNode) {
            throw new CucumberExpressionException(message + source);
        }
    }

    private void assertNoParameters(AstNode node, String message) {
        boolean hasParameter = node.getNodes().stream()
                .map(AstNode::getType)
                .anyMatch(type -> type == RuleType.Parameter);
        if (hasParameter) {
            throw new CucumberExpressionException(message + source);
        }
    }

    @Override
    public List<Argument<?>> match(String text, Type... typeHints) {
        final Group group = treeRegexp.match(text);
        if (group == null) {
            return null;
        }

        List<ParameterType<?>> parameterTypes = new ArrayList<>(this.parameterTypes);
        for (int i = 0; i < parameterTypes.size(); i++) {
            ParameterType<?> parameterType = parameterTypes.get(i);
            Type type = i < typeHints.length ? typeHints[i] : String.class;
            if (parameterType.isAnonymous()) {
                ParameterByTypeTransformer defaultTransformer = parameterTypeRegistry.getDefaultParameterTransformer();
                parameterTypes.set(i, parameterType.deAnonymize(type, arg -> defaultTransformer.transform(arg, type)));
            }
        }

        return Argument.build(group, treeRegexp, parameterTypes);
    }

    @Override
    public String getSource() {
        return source;
    }

    @Override
    public Pattern getRegexp() {
        return treeRegexp.pattern();
    }
}
