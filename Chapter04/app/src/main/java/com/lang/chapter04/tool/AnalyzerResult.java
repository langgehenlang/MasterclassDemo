package com.lang.chapter04.tool;

import com.squareup.haha.perflib.Instance;

public class AnalyzerResult {
	private String bufferHash;
	private String classInstance;
	private int width;
	private int height;
	private int bufferSize;
	private Instance instance;
	
	@Override
	public String toString() {
		return "bufferHash:" + this.bufferHash + "\n"
				+ "width:" + this.width + "\n"
				+ "height:" + this.height + "\n"
				+ "bufferSize:" + this.bufferSize;
	}

	public Instance getInstance() {
		return instance;
	}

	public void setInstance(Instance instance) {
		this.instance = instance;
	}

	public int getBufferSize() {
		return bufferSize;
	}

	public void setBufferSize(int bufferSize) {
		this.bufferSize = bufferSize;
	}

	public String getBufferHash() {
		return bufferHash;
	}

	public void setBufferHash(String bufferHash) {
		this.bufferHash = bufferHash;
	}

	public String getClassInstance() {
		return classInstance;
	}

	public void setClassInstance(String classInstance) {
		this.classInstance = classInstance;
	}

	public int getWidth() {
		return width;
	}

	public void setWidth(int width) {
		this.width = width;
	}

	public int getHeight() {
		return height;
	}

	public void setHeight(int height) {
		this.height = height;
	}
}