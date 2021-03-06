package com.googlecode.n_orm;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentMatcher;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import com.googlecode.n_orm.Incrementing.Mode;
import com.googlecode.n_orm.cf.SetColumnFamily;
import com.googlecode.n_orm.conversion.ConversionTools;
import com.googlecode.n_orm.storeapi.Constraint;
import com.googlecode.n_orm.storeapi.MetaInformation;
import com.googlecode.n_orm.storeapi.Row.ColumnFamilyData;
import com.googlecode.n_orm.storeapi.Store;

public class SendingToStoreTest {
	private static final String table = "SendingMetaToStoreTest";
	private static final String hash = "kolhkgiokbrvtfghi";
	private static final String id = hash+KeyManagement.KEY_END_SEPARATOR;
	private static final Field cfField;
	private static final Field propField;
	private static final Field incrField;
	
	@Persisting(table=table)
	public static class Element {
		private static final long serialVersionUID = 1L;
		@Key public String key;
		public String prop;
		@Incrementing(mode=Mode.Free) public int incr;
		public Set<String> cf = new TreeSet<String>();
		
		public Element(String key) {
			super();
			this.key = key;
		}
	}
	
	static {
		try {
			cfField = Element.class.getDeclaredField("cf");
			propField = Element.class.getDeclaredField("prop");
			incrField = Element.class.getDeclaredField("incr");
		} catch (RuntimeException x) {
			throw x;
		} catch (Exception x) {
			throw new RuntimeException(x);
		}
	}
	
	private Element element;
	@Mock private Store store;
	
	@Before
	public void setupStore() {
		MockitoAnnotations.initMocks(this);
		StoreSelector.getInstance().setPropertiesFor(Element.class, this.store);
		this.element = new Element(hash);
		//this.element.setStore(this.store);
		assertSame(this.store, this.element.getStore());
	}
	
	public Map<String, Field> getMap(boolean withProps, boolean withProp, boolean withIncr, boolean withCf) {
		Map<String, Field> ret = new TreeMap<String, Field>();
		if (withProps)
			ret.put(PropertyManagement.PROPERTY_COLUMNFAMILY_NAME, null);
		if (withProp)
			ret.put(propField.getName(), propField);
		if (withIncr)
			ret.put(incrField.getName(), incrField);
		if (withCf)
			ret.put(cfField.getName(), cfField);
		return ret;
	}
	
	public static <T extends Map<?,?>> ArgumentMatcher<T> getEmptyOrNullMapArgumentMatcher() {
		return new ArgumentMatcher<T>() {

			@Override
			public boolean matches(Object argument) {
				return argument == null || (
						(argument instanceof Map) &&
						((Map<?,?>)argument).isEmpty()
					);
			}
			
		};
	}
	
	public ColumnFamilyData getExpectedChange(final String expectedProChangeOrNull, final String... expectedCfValues) {
		if (expectedProChangeOrNull != null || (expectedCfValues != null && expectedCfValues.length > 0)) {
			return argThat(new ArgumentMatcher<ColumnFamilyData>() {

				@Override
				public boolean matches(Object argument) {
					if (argument == null)
						return false;
					
					ColumnFamilyData map = (ColumnFamilyData)argument;
					
					int expectedSize = 0;
					
					if (expectedProChangeOrNull != null) {
						expectedSize++;
						
						Map<String, byte[]> props = map.get(PropertyManagement.PROPERTY_COLUMNFAMILY_NAME);
						if (props == null || props.size() != 1)
							return false;
						
						byte[] val = props.get(propField.getName());
						if (val == null)
							return false;
						
						byte[] expected = ConversionTools.convert(expectedProChangeOrNull);
						if (! Arrays.equals(expected, val))
							return false;
					}
					
					if (expectedCfValues != null && expectedCfValues.length > 0) {
						expectedSize++;
						
						Map<String, byte[]> cf = map.get(cfField.getName());
						if (cf == null || cf.size() != expectedCfValues.length)
							return false;
						
						for (String qual : expectedCfValues) {
							byte[] val = cf.get(qual);
							if (val == null)
								return false;
							
							if (val.length != 0) //It's a set ; data are stored in qualifiers
								return false;
						}
					}
					
					if (map.size() != expectedSize)
						return false;
					
					return true;
				}
			});
		} else {
			return argThat(SendingToStoreTest.<ColumnFamilyData>getEmptyOrNullMapArgumentMatcher());
		}
	}
	
	public Map<String, Set<String>> getExpectedDelete(boolean deleteProp, String... deletedInCf) {
		if (deleteProp || (deletedInCf!=null && deletedInCf.length>0)) {
			Map<String, Set<String>> ret = new TreeMap<String, Set<String>>();
			if (deleteProp) {
				ret.put(PropertyManagement.PROPERTY_COLUMNFAMILY_NAME, Collections.singleton(propField.getName()));
			}
			if (deletedInCf != null && deletedInCf.length>0) {
				Set<String> deleted = new TreeSet<String>();
				ret.put(cfField.getName(), deleted);
				
				for (String deletedKey : deletedInCf) {
					deleted.add(deletedKey);
				}
			}
			return eq(ret);
		} else
			return argThat(SendingToStoreTest.<Map<String, Set<String>>>getEmptyOrNullMapArgumentMatcher());
	}
	
	public Map<String, Map<String, Number>> getExpectedIncr(final Integer expectedIncrement) {
		if (expectedIncrement != null) {
			return argThat(new ArgumentMatcher<Map<String, Map<String, Number>>>() {

				@Override
				public boolean matches(Object argument) {
					@SuppressWarnings("unchecked")
					Map<String, Map<String, Number>> map = (Map<String, Map<String, Number>>)argument;
					if (map == null || map.size() != 1)
						return false;

					Map<String, Number> incrs = map.get(PropertyManagement.PROPERTY_COLUMNFAMILY_NAME);
					if (incrs == null || incrs.size() != 1)
						return false;

					Number incr = incrs.get(incrField.getName());
					if (incr == null || incr.longValue() != expectedIncrement.longValue())
						return false;
					
					return true;
				}
				
			});
		} else {
			return argThat(SendingToStoreTest.<Map<String, Map<String, Number>>>getEmptyOrNullMapArgumentMatcher());
		}
	}
	
	@After
	public void checkNoMoreInteractions() {
		//verify(this.store, atMost(1)).start();
		verifyNoMoreInteractions(this.store);
	}
	
	@Test
	public void count() {
		StorageManagement.countElements(Element.class, null);
		verify(this.store).count(new MetaInformation().forClass(Element.class), table, null);
	}
	
	@Test
	public void search() {
		Constraint c = mock(Constraint.class);
		StorageManagement.findElement(Element.class, c, 10);
		Map<String, Field> cf = this.getMap(true, false, false, false);
		verify(this.store).get(new MetaInformation().forClass(Element.class).withColumnFamilies(cf), table, c, 10, cf.keySet());
	}
	
	@Test
	public void searchWithCf() {
		Constraint c = mock(Constraint.class);
		StorageManagement.findElement(Element.class, c, 10, cfField.getName());
		Map<String, Field> cf = this.getMap(true, false, false, true);
		verify(this.store).get(new MetaInformation().forClass(Element.class).withColumnFamilies(cf), table, c, 10, cf.keySet());
	}
	
	@Test
	public void delete() {
		this.element.delete();
		verify(this.store).delete(new MetaInformation().forElement(this.element), table, id);
		//Assertion checks whether element still exists after deletion
		verify(this.store, atMost(1)).exists(new MetaInformation().forElement(this.element), table, id);
	}
	
	@Test
	public void exists() {
		this.element.existsInStore();
		verify(this.store).exists(new MetaInformation().forElement(this.element), table, id);
	}
	
	@Test
	public void isEmptyCf() {
		((SetColumnFamily<?>)this.element.getColumnFamily(cfField.getName())).isEmptyInStore();
		verify(this.store).exists(new MetaInformation().forElement(this.element).forProperty(cfField), table, id, cfField.getName());
	}
	
	@Test
	public void containsCf() {
		((SetColumnFamily<?>)this.element.getColumnFamily(cfField.getName())).containsInStore("DummyKey");
		verify(this.store).get(new MetaInformation().forElement(this.element).forProperty(cfField), table, id, cfField.getName(), "DummyKey");
	}
	
	@Test
	public void activateSimple() {
		this.element.activate();
		Map<String, Field> cf = getMap(true, false, false, false);
		verify(this.store).get(new MetaInformation().forElement(this.element).withColumnFamilies(cf), table, id, cf.keySet());
	}
	
	@Test
	public void activateCf() {
		this.element.getColumnFamily(cfField.getName()).activate();
		verify(this.store).get(new MetaInformation().forElement(this.element).forProperty(cfField), table, id, cfField.getName());
	}
	
	@Test
	public void activateWithCf() {
		this.element.activate(cfField.getName());
		Map<String, Field> cf = getMap(true, false, false, true);
		verify(this.store).get(new MetaInformation().forElement(this.element).withColumnFamilies(cf), table, id, cf.keySet());
	}
	
	@Test
	public void storeSimple() {
		this.element.store();
		verify(this.store).storeChanges(eq(new MetaInformation().forElement(this.element).withColumnFamilies(this.getMap(false, false, false, false))), eq(table), eq(id), this.getExpectedChange(null), this.getExpectedDelete(false), getExpectedIncr(null));
	}
	
	@Test
	public void storeWithPropAndDelete() {
		this.element.prop = "1234856453";
		this.element.store();
		verify(this.store).storeChanges(eq(new MetaInformation().forElement(this.element).withColumnFamilies(this.getMap(false, true, false, false))), eq(table), eq(id), this.getExpectedChange(this.element.prop), this.getExpectedDelete(false), getExpectedIncr(null));
		
		this.element.prop = null;
		this.element.store();
		verify(this.store).storeChanges(eq(new MetaInformation().forElement(this.element).withColumnFamilies(this.getMap(false, true, false, false))), eq(table), eq(id), this.getExpectedChange(null), this.getExpectedDelete(true), getExpectedIncr(null));
	}
	
	@Test
	public void storeWithIncr() {
		this.element.incr += 12;
		this.element.store();
		verify(this.store).storeChanges(eq(new MetaInformation().forElement(this.element).withColumnFamilies(this.getMap(false, false, true, false))), eq(table), eq(id), this.getExpectedChange(null), this.getExpectedDelete(false), getExpectedIncr(12));
	}
	
	@Test
	public void storeWithCFAndDelete() {
		this.element.cf.add("DummyKey1");
		this.element.cf.add("DummyKey2");
		this.element.store();
		verify(this.store).storeChanges(eq(new MetaInformation().forElement(this.element).withColumnFamilies(this.getMap(false, false, false, true))), eq(table), eq(id), this.getExpectedChange(null, "DummyKey2", "DummyKey1"), this.getExpectedDelete(false), getExpectedIncr(null));
		
		this.element.cf.remove("DummyKey2");
		this.element.store();
		verify(this.store).storeChanges(eq(new MetaInformation().forElement(this.element).withColumnFamilies(this.getMap(false, false, false, true))), eq(table), eq(id), this.getExpectedChange(null), this.getExpectedDelete(false, "DummyKey2"), getExpectedIncr(null));
	}
	
	@Test
	public void storeWithAllAndDeleteAll() {
		this.element.prop = "1234856453";
		this.element.incr += 12;
		this.element.cf.add("DummyKey1");
		this.element.cf.add("DummyKey2");
		this.element.store();
		verify(this.store).storeChanges(eq(new MetaInformation().forElement(this.element).withColumnFamilies(this.getMap(false, true, true, true))), eq(table), eq(id), this.getExpectedChange(this.element.prop, "DummyKey2", "DummyKey1"), this.getExpectedDelete(false), getExpectedIncr(12));

		this.element.prop = null;
		this.element.cf.remove("DummyKey2");
		this.element.store();
		verify(this.store).storeChanges(eq(new MetaInformation().forElement(this.element).withColumnFamilies(this.getMap(false, true, false, true))), eq(table), eq(id), this.getExpectedChange(null), this.getExpectedDelete(true, "DummyKey2"), getExpectedIncr(null));
	}
	
}
