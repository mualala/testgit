package org.seckill.service.impl;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.collections.MapUtils;
import org.seckill.dao.SeckillDao;
import org.seckill.dao.SuccessKilledDao;
import org.seckill.dao.cache.RedisDao;
import org.seckill.dto.Exposer;
import org.seckill.dto.SeckillExecution;
import org.seckill.entity.Seckill;
import org.seckill.entity.SuccessKilled;
import org.seckill.enums.SeckillStateEnum;
import org.seckill.exception.RepeatKillException;
import org.seckill.exception.SeckillCloseException;
import org.seckill.exception.SeckillException;
import org.seckill.service.SeckillService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.DigestUtils;

@Service
public class SeckillServiceImpl implements SeckillService {
	private Logger logger = LoggerFactory.getLogger(this.getClass());
	
	@Autowired//@Resource,@Inject
	private SeckillDao seckillDao;
	@Autowired
	private SuccessKilledDao successKilledDao;
	@Autowired
	private RedisDao redisDao;
	
	//md5的盐值
	public final String salt="isdjawoqeu9eudwqxsiaha";
	
	public List<Seckill> getSeckillList() {
		return seckillDao.queryAll(0, 4);
	}

	public Seckill getById(long seckillId) {
		return seckillDao.queryById(seckillId);
	}

	public Exposer exportSeckillUrl(long seckillId) {
		//优化点 : 缓存优化,超时的基础上维护一致性
		//1.先访问redis
		Seckill seckill = redisDao.getSeckill(seckillId);
		if (seckill == null) {
			//2.访问数据库
			seckill = seckillDao.queryById(seckillId);
			if (seckill == null) {
				return new Exposer(false, seckillId);
			}else {
				//3.放入redis
				redisDao.putSeckill(seckill);
			}
		}
		Date startTime = seckill.getStartTime();
		Date endTime = seckill.getEndTime();
		//系统当前时间
		Date nowTime = new Date();
		if(nowTime.getTime()<startTime.getTime() || nowTime.getTime()>endTime.getTime()){
			return new Exposer(false, seckillId, nowTime.getTime(), startTime.getTime(), endTime.getTime());
		}
		String md5 = getMD5(seckillId);//TODO
		return new Exposer(true, md5, seckillId);
	}
	
	private String getMD5(long seckillId){
		String base = seckillId+"/"+salt;
		String md5 = DigestUtils.md5DigestAsHex(base.getBytes());
		return md5;
	}
	
	@Transactional
	/*
	 * 使用注解控制事务方法的有点:
	 * 1.开发团队达成一致约定,明确标注事务方法的变成风格
	 * 2.保证事务方法的执行时间尽可能短,不要穿插其他的网络操作RFC/HTTP请求或者剥离到事务方法外部
	 * 3.不是所有的方法都需要事务,只有一条添加操作,只读操作不需要事务控制
	 */
	public SeckillExecution executeSeckill(long seckillId, long userPhone,
			String md5) throws SeckillException, RepeatKillException,
			SeckillCloseException {
		if(md5==null || !md5.equals(getMD5(seckillId))){
			throw new SeckillException("seckill data rewrite");
		}
		//执行秒杀逻辑:减库存+记录购买行为
		Date nowTime = new Date();
		try {
			//记录购买行为
			int insertCount = successKilledDao.insertSuccessKilled(seckillId, userPhone);
			if(insertCount<=0){
				//重复秒杀
				throw new RepeatKillException("seckill repeated");
			}else {
				//减库存,热点商品竞争
				int updateCount = seckillDao.reduceNumber(seckillId, nowTime);
				if(updateCount<=0){
					//没有更新到记录,秒杀结束 rollback
					throw new SeckillCloseException("seckill is closed");
				}else {
					//秒杀成功 commit
					SuccessKilled successKilled = successKilledDao.queryByIdWithSeckill(seckillId, userPhone);
					return new SeckillExecution(seckillId, SeckillStateEnum.SUCCESS, successKilled);
				}
			}
		} catch (SeckillCloseException e1){
			throw e1;
		} catch (RepeatKillException e2){
			throw e2;
		} catch (Exception e) {
			logger.error(e.getMessage(), e);
			//所有编译期异常转化为运行期异常
			throw new SeckillException("seckill inner error:"+e.getMessage());
		}
	}

	public SeckillExecution executeSeckillProcedure(long seckillId,
			long userPhone, String md5) {
		if(md5 == null || !md5.equals(getMD5(seckillId))){
			return new SeckillExecution(seckillId, SeckillStateEnum.DATE_REWRITE);
		}
		Date killTime = new Date();
		Map<String, Object> params = new HashMap<String, Object>();
		params.put("seckillId", seckillId);
		params.put("phone", userPhone);
		params.put("killTime", killTime);
		params.put("result", null);
		//执行存储过程,result被赋值
		try {
			seckillDao.killByProcedure(params);
			//获取result
			int result = MapUtils.getInteger(params, "result",-2);
			if(result == 1){
				SuccessKilled sk = successKilledDao.queryByIdWithSeckill(seckillId, userPhone);
				return new SeckillExecution(seckillId,SeckillStateEnum.SUCCESS,sk);
			}else {
				return new SeckillExecution(seckillId, SeckillStateEnum.stateOf(result));
			}
		} catch (Exception e) {
			logger.error(e.getMessage(),e);
			return new SeckillExecution(seckillId, SeckillStateEnum.INNER_ERROR);
		}
	}
	
	
}
