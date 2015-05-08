package be.kuleuven.cs.mas.message;

import com.google.common.base.Optional;

public class Field {

	public Field(String name, String value) throws IllegalArgumentException {
		if (name == null) {
			throw new IllegalArgumentException("name cannot be null");
		}
		if (value == null) {
			throw new IllegalArgumentException("value cannot be null");
		}
		this.name = name;
		this.value = Optional.of(value);
	}
	
	public Field(String name) throws IllegalArgumentException {
		if (name == null) {
			throw new IllegalArgumentException("name cannot be null");
		}
		this.name = name;
		this.value = Optional.absent();
	}
	
	private String name;
	private Optional<String> value;
	
	public String getName() {
		return this.name;
	}
	
	public String getValue() throws IllegalStateException {
		if (! this.hasValue()) {
			throw new IllegalStateException("field has no value");
		}
		return this.getValuePriv().get();
	}
	
	private Optional<String> getValuePriv() {
		return this.value;
	}
	
	public boolean hasValue() {
		return this.getValuePriv().isPresent();
	}
	
	public String toString() {
		if (this.hasValue()) {
			return this.getName() + MessageContents.NAME_VALUE_SEP + this.getValue() + MessageContents.FIELD_SEP;
		} else {
			return this.getName() + MessageContents.FIELD_SEP;
		}
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((name == null) ? 0 : name.hashCode());
		result = prime * result + ((value == null) ? 0 : value.hashCode());
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		Field other = (Field) obj;
		if (name == null) {
			if (other.name != null)
				return false;
		} else if (!name.equals(other.name))
			return false;
		if (value == null) {
			if (other.value != null)
				return false;
		} else if (!value.equals(other.value))
			return false;
		return true;
	}
}