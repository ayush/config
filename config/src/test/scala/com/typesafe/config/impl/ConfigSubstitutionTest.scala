/**
 *   Copyright (C) 2011 Typesafe Inc. <http://typesafe.com>
 */
package com.typesafe.config.impl

import org.junit.Assert._
import org.junit._
import com.typesafe.config.ConfigValue
import com.typesafe.config.ConfigException
import com.typesafe.config.ConfigResolveOptions
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory

class ConfigSubstitutionTest extends TestUtils {

    private def resolveWithoutFallbacks(v: AbstractConfigObject) = {
        val options = ConfigResolveOptions.noSystem()
        SubstitutionResolver.resolve(v, v, options).asInstanceOf[AbstractConfigObject].toConfig
    }
    private def resolveWithoutFallbacks(s: ConfigSubstitution, root: AbstractConfigObject) = {
        val options = ConfigResolveOptions.noSystem()
        SubstitutionResolver.resolve(s, root, options)
    }

    private def resolve(v: AbstractConfigObject) = {
        val options = ConfigResolveOptions.defaults()
        SubstitutionResolver.resolve(v, v, options).asInstanceOf[AbstractConfigObject].toConfig
    }
    private def resolve(s: ConfigSubstitution, root: AbstractConfigObject) = {
        val options = ConfigResolveOptions.defaults()
        SubstitutionResolver.resolve(s, root, options)
    }

    private val simpleObject = {
        parseObject("""
{
   "foo" : 42,
   "bar" : {
       "int" : 43,
       "bool" : true,
       "null" : null,
       "string" : "hello",
       "double" : 3.14
    }
}
""")
    }

    @Test
    def resolveTrivialKey() {
        val s = subst("foo")
        val v = resolveWithoutFallbacks(s, simpleObject)
        assertEquals(intValue(42), v)
    }

    @Test
    def resolveTrivialPath() {
        val s = subst("bar.int")
        val v = resolveWithoutFallbacks(s, simpleObject)
        assertEquals(intValue(43), v)
    }

    @Test
    def resolveInt() {
        val s = subst("bar.int")
        val v = resolveWithoutFallbacks(s, simpleObject)
        assertEquals(intValue(43), v)
    }

    @Test
    def resolveBool() {
        val s = subst("bar.bool")
        val v = resolveWithoutFallbacks(s, simpleObject)
        assertEquals(boolValue(true), v)
    }

    @Test
    def resolveNull() {
        val s = subst("bar.null")
        val v = resolveWithoutFallbacks(s, simpleObject)
        assertEquals(nullValue(), v)
    }

    @Test
    def resolveString() {
        val s = subst("bar.string")
        val v = resolveWithoutFallbacks(s, simpleObject)
        assertEquals(stringValue("hello"), v)
    }

    @Test
    def resolveDouble() {
        val s = subst("bar.double")
        val v = resolveWithoutFallbacks(s, simpleObject)
        assertEquals(doubleValue(3.14), v)
    }

    @Test
    def resolveMissingThrows() {
        intercept[ConfigException.UnresolvedSubstitution] {
            val s = subst("bar.missing")
            val v = resolveWithoutFallbacks(s, simpleObject)
        }
    }

    @Test
    def resolveIntInString() {
        val s = substInString("bar.int")
        val v = resolveWithoutFallbacks(s, simpleObject)
        assertEquals(stringValue("start<43>end"), v)
    }

    @Test
    def resolveNullInString() {
        val s = substInString("bar.null")
        val v = resolveWithoutFallbacks(s, simpleObject)
        assertEquals(stringValue("start<null>end"), v)

        // when null is NOT a subst, it should also not become empty
        val o = parseConfig("""{ "a" : null foo bar }""")
        assertEquals("null foo bar", o.getString("a"))
    }

    @Test
    def resolveMissingInString() {
        val s = substInString("bar.missing", true /* optional */ )
        val v = resolveWithoutFallbacks(s, simpleObject)
        // absent object becomes empty string
        assertEquals(stringValue("start<>end"), v)

        intercept[ConfigException.UnresolvedSubstitution] {
            val s2 = substInString("bar.missing", false /* optional */ )
            resolveWithoutFallbacks(s2, simpleObject)
        }
    }

    @Test
    def resolveBoolInString() {
        val s = substInString("bar.bool")
        val v = resolveWithoutFallbacks(s, simpleObject)
        assertEquals(stringValue("start<true>end"), v)
    }

    @Test
    def resolveStringInString() {
        val s = substInString("bar.string")
        val v = resolveWithoutFallbacks(s, simpleObject)
        assertEquals(stringValue("start<hello>end"), v)
    }

    @Test
    def resolveDoubleInString() {
        val s = substInString("bar.double")
        val v = resolveWithoutFallbacks(s, simpleObject)
        assertEquals(stringValue("start<3.14>end"), v)
    }

    @Test
    def missingInArray() {
        import scala.collection.JavaConverters._

        val obj = parseObject("""
    a : [ ${?missing}, ${?also.missing} ]
""")

        val resolved = resolve(obj)

        assertEquals(Seq(), resolved.getList("a").asScala)
    }

    @Test
    def missingInObject() {
        import scala.collection.JavaConverters._

        val obj = parseObject("""
    a : ${?missing}, b : ${?also.missing}, c : ${?b}, d : ${?c}
""")

        val resolved = resolve(obj)

        assertTrue(resolved.isEmpty())
    }

    private val substChainObject = {
        parseObject("""
{
    "foo" : ${bar},
    "bar" : ${a.b.c},
    "a" : { "b" : { "c" : 57 } }
}
""")
    }

    @Test
    def chainSubstitutions() {
        val s = subst("foo")
        val v = resolveWithoutFallbacks(s, substChainObject)
        assertEquals(intValue(57), v)
    }

    private val substCycleObject = {
        parseObject("""
{
    "foo" : ${bar},
    "bar" : ${a.b.c},
    "a" : { "b" : { "c" : ${foo} } }
}
""")
    }

    @Test
    def throwOnCycles() {
        val s = subst("foo")
        val e = intercept[ConfigException.BadValue] {
            val v = resolveWithoutFallbacks(s, substCycleObject)
        }
        assertTrue(e.getMessage().contains("cycle"))
    }

    @Test
    def resolveObject() {
        val resolved = resolveWithoutFallbacks(substChainObject)
        assertEquals(57, resolved.getInt("foo"))
        assertEquals(57, resolved.getInt("bar"))
        assertEquals(57, resolved.getInt("a.b.c"))
    }

    private val substSideEffectCycle = {
        parseObject("""
{
    "foo" : ${a.b.c},
    "a" : { "b" : { "c" : 42, "cycle" : ${foo} }, "cycle" : ${foo} }
}
""")
    }

    @Test
    def avoidSideEffectCycles() {
        // The point of this test is that in traversing objects
        // to resolve a path, we need to avoid resolving
        // substitutions that are in the traversed objects but
        // are not directly required to resolve the path.
        // i.e. there should not be a cycle in this test.

        val resolved = resolveWithoutFallbacks(substSideEffectCycle)

        assertEquals(42, resolved.getInt("foo"))
        assertEquals(42, resolved.getInt("a.b.cycle"))
        assertEquals(42, resolved.getInt("a.cycle"))
    }

    @Test
    def useRelativeToSameFileWhenRelativized() {
        val child = parseObject("""foo=in child,bar=${foo}""")

        val values = new java.util.HashMap[String, AbstractConfigValue]()

        values.put("a", child.relativized(new Path("a")))
        // this "foo" should NOT be used.
        values.put("foo", stringValue("in parent"));

        val resolved = resolve(new SimpleConfigObject(fakeOrigin(), values));

        assertEquals("in child", resolved.getString("a.bar"))
    }

    @Test
    def useRelativeToRootWhenRelativized() {
        // here, "foo" is not defined in the child
        val child = parseObject("""bar=${foo}""")

        val values = new java.util.HashMap[String, AbstractConfigValue]()

        values.put("a", child.relativized(new Path("a")))
        // so this "foo" SHOULD be used
        values.put("foo", stringValue("in parent"));

        val resolved = resolve(new SimpleConfigObject(fakeOrigin(), values));

        assertEquals("in parent", resolved.getString("a.bar"))
    }

    private val substComplexObject = {
        parseObject("""
{
    "foo" : ${bar},
    "bar" : ${a.b.c},
    "a" : { "b" : { "c" : 57, "d" : ${foo}, "e" : { "f" : ${foo} } } },
    "objA" : ${a},
    "objB" : ${a.b},
    "objE" : ${a.b.e},
    "foo.bar" : 37,
    "arr" : [ ${foo}, ${a.b.c}, ${"foo.bar"}, ${objB.d}, ${objA.b.e.f}, ${objE.f} ],
    "ptrToArr" : ${arr},
    "x" : { "y" : { "ptrToPtrToArr" : ${ptrToArr} } }
}
""")
    }

    @Test
    def complexResolve() {
        import scala.collection.JavaConverters._

        val resolved = resolveWithoutFallbacks(substComplexObject)

        assertEquals(57, resolved.getInt("foo"))
        assertEquals(57, resolved.getInt("bar"))
        assertEquals(57, resolved.getInt("a.b.c"))
        assertEquals(57, resolved.getInt("a.b.d"))
        assertEquals(57, resolved.getInt("objB.d"))
        assertEquals(Seq(57, 57, 37, 57, 57, 57), resolved.getIntList("arr").asScala)
        assertEquals(Seq(57, 57, 37, 57, 57, 57), resolved.getIntList("ptrToArr").asScala)
        assertEquals(Seq(57, 57, 37, 57, 57, 57), resolved.getIntList("x.y.ptrToPtrToArr").asScala)
    }

    private val substSystemPropsObject = {
        parseObject("""
{
    "a" : ${configtest.a},
    "b" : ${configtest.b}
}
""")
    }

    // this is a weird test, it used to test fallback to system props which made more sense.
    // Now it just tests that if you override with system props, you can use system props
    // in substitutions.
    @Test
    def overrideWithSystemProps() {
        System.setProperty("configtest.a", "1234")
        System.setProperty("configtest.b", "5678")
        ConfigImpl.reloadSystemPropertiesConfig()

        val resolved = resolve(ConfigFactory.systemProperties().withFallback(substSystemPropsObject).root.asInstanceOf[AbstractConfigObject])

        assertEquals("1234", resolved.getString("a"))
        assertEquals("5678", resolved.getString("b"))
    }

    private val substEnvVarObject = {
        parseObject("""
{
    "home" : ${?HOME},
    "pwd" : ${?PWD},
    "shell" : ${?SHELL},
    "lang" : ${?LANG},
    "path" : ${?PATH},
    "not_here" : ${?NOT_HERE}
}
""")
    }

    @Test
    def fallbackToEnv() {
        import scala.collection.JavaConverters._

        val resolved = resolve(substEnvVarObject)

        var existed = 0
        for (k <- resolved.root.keySet().asScala) {
            val e = System.getenv(k.toUpperCase());
            if (e != null) {
                existed += 1
                assertEquals(e, resolved.getString(k))
            } else {
                assertNull(resolved.root.get(k))
            }
        }
        if (existed == 0) {
            throw new Exception("None of the env vars we tried to use for testing were set")
        }
    }

    @Test
    def noFallbackToEnvIfValuesAreNull() {
        import scala.collection.JavaConverters._

        // create a fallback object with all the env var names
        // set to null. we want to be sure this blocks
        // lookup in the environment. i.e. if there is a
        // { HOME : null } then ${HOME} should be null.
        val nullsMap = new java.util.HashMap[String, Object]
        for (k <- substEnvVarObject.keySet().asScala) {
            nullsMap.put(k.toUpperCase(), null);
        }
        val nulls = ConfigFactory.parseMap(nullsMap, "nulls map")

        val resolved = resolve(substEnvVarObject.withFallback(nulls))

        for (k <- resolved.root.keySet().asScala) {
            assertNotNull(resolved.root.get(k))
            assertEquals(nullValue, resolved.root.get(k))
        }
    }

    @Test
    def fallbackToEnvWhenRelativized() {
        import scala.collection.JavaConverters._

        val values = new java.util.HashMap[String, AbstractConfigValue]()

        values.put("a", substEnvVarObject.relativized(new Path("a")))

        val resolved = resolve(new SimpleConfigObject(fakeOrigin(), values));

        var existed = 0
        for (k <- resolved.getObject("a").keySet().asScala) {
            val e = System.getenv(k.toUpperCase());
            if (e != null) {
                existed += 1
                assertEquals(e, resolved.getConfig("a").getString(k))
            } else {
                assertNull(resolved.getObject("a").get(k))
            }
        }
        if (existed == 0) {
            throw new Exception("None of the env vars we tried to use for testing were set")
        }
    }

    @Test
    def throwWhenEnvNotFound() {
        val obj = parseObject("""{ a : ${NOT_HERE} }""")
        intercept[ConfigException.UnresolvedSubstitution] {
            resolve(obj)
        }
    }

    @Test
    def optionalOverrideNotProvided() {
        val obj = parseObject("""{ a: 42, a : ${?NOT_HERE} }""")
        val resolved = resolve(obj)
        assertEquals(42, resolved.getInt("a"))
    }

    @Test
    def optionalOverrideProvided() {
        val obj = parseObject("""{ HERE : 43, a: 42, a : ${?HERE} }""")
        val resolved = resolve(obj)
        assertEquals(43, resolved.getInt("a"))
    }

    @Test
    def optionalOverrideOfObjectNotProvided() {
        val obj = parseObject("""{ a: { b : 42 }, a : ${?NOT_HERE} }""")
        val resolved = resolve(obj)
        assertEquals(42, resolved.getInt("a.b"))
    }

    @Test
    def optionalOverrideOfObjectProvided() {
        val obj = parseObject("""{ HERE : 43, a: { b : 42 }, a : ${?HERE} }""")
        val resolved = resolve(obj)
        assertEquals(43, resolved.getInt("a"))
        assertFalse(resolved.hasPath("a.b"))
    }

    @Test
    def optionalVanishesFromArray() {
        import scala.collection.JavaConverters._
        val obj = parseObject("""{ a : [ 1, 2, 3, ${?NOT_HERE} ] }""")
        val resolved = resolve(obj)
        assertEquals(Seq(1, 2, 3), resolved.getIntList("a").asScala)
    }

    @Test
    def optionalUsedInArray() {
        import scala.collection.JavaConverters._
        val obj = parseObject("""{ HERE: 4, a : [ 1, 2, 3, ${?HERE} ] }""")
        val resolved = resolve(obj)
        assertEquals(Seq(1, 2, 3, 4), resolved.getIntList("a").asScala)
    }
}
